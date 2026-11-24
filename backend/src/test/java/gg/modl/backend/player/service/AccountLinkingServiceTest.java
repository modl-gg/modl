package gg.modl.backend.player.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.iplookup.model.VpnStatus;
import gg.modl.backend.iplookup.service.AddressForwardingMonitor;
import gg.modl.backend.iplookup.settings.PlayerAddressSettings;
import gg.modl.backend.iplookup.settings.PlayerAddressSettingsStore;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.UsernameEntry;
import gg.modl.backend.player.dto.response.LinkedAccountResponse;
import gg.modl.backend.player.repository.ActiveCounts;
import gg.modl.backend.player.repository.AddressShare;
import gg.modl.backend.player.repository.LinkCandidate;
import gg.modl.backend.player.repository.PlayerParts;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.MutableClock;
import gg.modl.backend.support.TestServers;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountLinkingServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String HOME = "203.0.113.7";
    private static final String OFFICE = "203.0.113.8";

    private final Server server = TestServers.server("server", "admin@example.com");
    private final UUID playerUuid = UUID.randomUUID();

    @Mock
    private PlayerRepository playerRepository;

    @Mock
    private PlayerAddressSettingsStore addressSettings;

    @Mock
    private AddressForwardingMonitor forwardingMonitor;

    private AccountLinkingService accountLinkingService;

    @BeforeEach
    void setUp() {
        accountLinkingService = new AccountLinkingService(playerRepository, addressSettings, forwardingMonitor,
            new MutableClock(NOW));
    }

    private void sharedAccountLimit(int limit) {
        when(addressSettings.current()).thenReturn(PlayerAddressSettings.EMPTY.withSharedAccountLimit(limit));
    }

    @Test
    void findAndLinkAccountsLinksTheCandidatesAndCountsThem() {
        sharedAccountLimit(10);
        Player player = Player.builder().id("player-1").minecraftUuid(playerUuid).build();
        List<LinkCandidate> candidates = List.of(LinkCandidate.unverified("player-2"));
        when(playerRepository.findByMinecraftUuid(playerUuid.toString())).thenReturn(Optional.of(player));
        when(playerRepository.addressShares("player-1")).thenReturn(List.of(new AddressShare(HOME, 2, VpnStatus.UNKNOWN)));
        when(playerRepository.findLinkCandidates("player-1", List.of(HOME), 1000)).thenReturn(candidates);
        when(playerRepository.link("player-1", candidates, NOW)).thenReturn(1);

        AccountLinkingService.LinkingResult result = accountLinkingService.findAndLinkAccounts(server, playerUuid);

        assertTrue(result.success());
        assertEquals(1, result.linkedAccountsFound());
    }

    @Test
    void steadyStateOpensNoWrite() {
        sharedAccountLimit(10);
        when(playerRepository.addressShares("player-1")).thenReturn(List.of(new AddressShare(HOME, 2, VpnStatus.NO)));
        when(playerRepository.findLinkCandidates("player-1", List.of(HOME), 1000)).thenReturn(List.of());

        assertEquals(0, accountLinkingService.linkNewMatches("player-1", NOW));

        verify(playerRepository, never()).link(anyString(), any(), any());
    }

    @Test
    void privateLoopbackLinkLocalAndCarrierGradeNatAddressesNeverLink() {
        sharedAccountLimit(10);
        when(playerRepository.addressShares("player-1")).thenReturn(List.of(
            new AddressShare("127.0.0.1", 2, VpnStatus.UNKNOWN),
            new AddressShare("10.0.0.4", 2, VpnStatus.UNKNOWN),
            new AddressShare("172.17.0.2", 2, VpnStatus.UNKNOWN),
            new AddressShare("192.168.1.20", 2, VpnStatus.UNKNOWN),
            new AddressShare("169.254.3.3", 2, VpnStatus.UNKNOWN),
            new AddressShare("100.64.0.9", 2, VpnStatus.UNKNOWN),
            new AddressShare("fd00::1", 2, VpnStatus.UNKNOWN),
            new AddressShare("::1", 2, VpnStatus.UNKNOWN)));

        assertEquals(0, accountLinkingService.linkNewMatches("player-1", NOW));

        verify(playerRepository, never()).findLinkCandidates(anyString(), any(), anyInt());
        verify(forwardingMonitor, never()).sharedAddressPaused();
    }

    @Test
    void aVpnAddressNeverLinks() {
        sharedAccountLimit(10);
        when(playerRepository.addressShares("player-1")).thenReturn(List.of(new AddressShare(HOME, 2, VpnStatus.YES)));

        assertEquals(0, accountLinkingService.linkNewMatches("player-1", NOW));

        verify(playerRepository, never()).findLinkCandidates(anyString(), any(), anyInt());
    }

    @Test
    void anAddressSharedByMoreAccountsThanTheLimitIsPausedAndTheOwnerIsTold() {
        sharedAccountLimit(3);
        when(playerRepository.addressShares("player-1")).thenReturn(List.of(
            new AddressShare(HOME, 4, VpnStatus.NO),
            new AddressShare(OFFICE, 3, VpnStatus.NO)));
        when(playerRepository.findLinkCandidates("player-1", List.of(OFFICE), 1000)).thenReturn(List.of());

        accountLinkingService.linkNewMatches("player-1", NOW);

        verify(playerRepository).findLinkCandidates("player-1", List.of(OFFICE), 1000);
        verify(forwardingMonitor).sharedAddressPaused();
    }

    @Test
    void aClearedAddressRelinksEveryAccountUsingItWhileUnderTheLimit() {
        sharedAccountLimit(3);
        when(playerRepository.findPlayerIdsByAddress(HOME, 4)).thenReturn(List.of("player-1", "player-2"));
        when(playerRepository.addressShares(anyString())).thenReturn(List.of(new AddressShare(HOME, 2, VpnStatus.NO)));
        when(playerRepository.findLinkCandidates(anyString(), any(), anyInt())).thenReturn(List.of());

        accountLinkingService.linkAddressSharers(HOME, NOW);

        verify(playerRepository).addressShares("player-1");
        verify(playerRepository).addressShares("player-2");
    }

    @Test
    void aClearedAddressOverTheLimitRelinksNobody() {
        sharedAccountLimit(3);
        when(playerRepository.findPlayerIdsByAddress(HOME, 4)).thenReturn(List.of("a", "b", "c", "d"));

        accountLinkingService.linkAddressSharers(HOME, NOW);

        verify(playerRepository, never()).addressShares(anyString());
    }

    @Test
    void findAndLinkAccountsReportsAnUnknownPlayer() {
        when(playerRepository.findByMinecraftUuid(playerUuid.toString())).thenReturn(Optional.empty());

        assertFalse(accountLinkingService.findAndLinkAccounts(server, playerUuid).success());
    }

    @Test
    void linkedAccountsCarryTheGroupedActiveCounts() {
        UUID linkedUuid = UUID.randomUUID();
        Player player = Player.builder().id("player-1").minecraftUuid(playerUuid)
            .data(new HashMap<>(Map.of("linkedAccounts", List.of(linkedUuid.toString())))).build();
        Player linked = Player.builder().id("player-2").minecraftUuid(linkedUuid)
            .usernames(List.of(new UsernameEntry("Alt", null))).build();
        when(playerRepository.findByMinecraftUuid(playerUuid.toString(), PlayerParts.LINKS)).thenReturn(Optional.of(player));
        when(playerRepository.findByMinecraftUuids(List.of(linkedUuid.toString()))).thenReturn(List.of(linked));
        when(playerRepository.countActiveByPlayer(List.of("player-2"), NOW))
            .thenReturn(Map.of("player-2", new ActiveCounts(2, 1)));

        List<LinkedAccountResponse> accounts = accountLinkingService.getLinkedAccounts(server, playerUuid);

        assertEquals(List.of(new LinkedAccountResponse(linkedUuid.toString(), "Alt", 2, 1, null)), accounts);
    }
}
