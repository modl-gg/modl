package gg.modl.backend.auth.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.repository.PanelLoginTokenRepository;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeCodec;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.service.MinecraftStaffService;
import gg.modl.backend.support.TestServers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PanelLoginLinkServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final String UUID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String EMAIL = "mod@example.com";
    private static final String TOKEN = "login-token-0123456789";

    private PanelLoginTokenRepository tokenRepository;
    private MinecraftStaffService minecraftStaffService;
    private PlayerRepository playerRepository;
    private PermissionService permissionService;
    private PublicUrls publicUrls;
    private PanelActionAuditor auditor;
    private OneTimeCodeCodec codec;
    private PanelLoginLinkService service;
    private Server server;

    @BeforeEach
    void setUp() {
        tokenRepository = mock(PanelLoginTokenRepository.class);
        minecraftStaffService = mock(MinecraftStaffService.class);
        playerRepository = mock(PlayerRepository.class);
        permissionService = mock(PermissionService.class);
        publicUrls = mock(PublicUrls.class);
        auditor = mock(PanelActionAuditor.class);
        codec = new OneTimeCodeCodec(() -> "secret");
        IdGenerator idGenerator = mock(IdGenerator.class);
        when(idGenerator.generateToken()).thenReturn(TOKEN);
        server = TestServers.server("Server", "owner@example.com");
        service = new PanelLoginLinkService(tokenRepository, minecraftStaffService, playerRepository, permissionService,
            codec, idGenerator, publicUrls, auditor, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void unlinkedPlayersGetNoLink() {
        when(minecraftStaffService.findMinecraftAssignee(server, UUID)).thenReturn(Optional.empty());

        assertThat(service.issue(server, UUID)).isInstanceOf(LoginLinkIssue.NotLinked.class);
        verify(tokenRepository, never()).insert(any());
    }

    @Test
    void offlinePlayersGetNoLink() {
        linkedAndAuthorized();
        when(playerRepository.findByMinecraftUuid(UUID)).thenReturn(Optional.of(player(false)));

        assertThat(service.issue(server, UUID)).isInstanceOf(LoginLinkIssue.NotOnline.class);
        verify(tokenRepository, never()).insert(any());
    }

    @Test
    void onlineLinkedStaffGetAHashedSingleUseTokenAndTheIssueIsAudited() {
        linkedAndAuthorized();
        when(playerRepository.findByMinecraftUuid(UUID)).thenReturn(Optional.of(player(true)));
        when(publicUrls.loginLink(TOKEN)).thenReturn("https://panel.example.com/auth/link?token=" + TOKEN);

        LoginLinkIssue issue = service.issue(server, UUID);

        assertThat(issue).isEqualTo(new LoginLinkIssue.Issued("https://panel.example.com/auth/link?token=" + TOKEN, 120));
        ArgumentCaptor<PanelLoginToken> saved = ArgumentCaptor.forClass(PanelLoginToken.class);
        verify(tokenRepository).insert(saved.capture());
        assertThat(saved.getValue().getId()).isNotBlank();
        assertThat(saved.getValue().getTokenHash()).isEqualTo(codec.hash(TOKEN)).isNotEqualTo(TOKEN);
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plusSeconds(120));
        verify(auditor).recordStaffAction(eq(server), eq(EMAIL), anyString());
    }

    @Test
    void consumingAValidTokenSignsTheIdentityInAndIsAudited() {
        issuedToken();
        when(permissionService.isAuthorizedEmail(server, EMAIL)).thenReturn(true);
        when(minecraftStaffService.isAssignedTo(server, EMAIL, UUID)).thenReturn(true);

        assertThat(service.consume(server, TOKEN)).contains(EMAIL);
        verify(auditor).recordStaffAction(eq(server), eq(EMAIL), anyString());
    }

    @Test
    void consumingATokenOfARemovedStaffMemberFailsWithoutAnAuditEntry() {
        issuedToken();
        when(permissionService.isAuthorizedEmail(server, EMAIL)).thenReturn(false);
        when(minecraftStaffService.isAssignedTo(server, EMAIL, UUID)).thenReturn(true);

        assertThat(service.consume(server, TOKEN)).isEmpty();
        verify(auditor, never()).recordStaffAction(any(), any(), any());
    }

    @Test
    void consumingATokenAfterTheMinecraftAccountWasUnlinkedFails() {
        issuedToken();
        when(permissionService.isAuthorizedEmail(server, EMAIL)).thenReturn(true);
        when(minecraftStaffService.isAssignedTo(server, EMAIL, UUID)).thenReturn(false);

        assertThat(service.consume(server, TOKEN)).isEmpty();
        verify(auditor, never()).recordStaffAction(any(), any(), any());
    }

    @Test
    void aMalformedUuidGetsNoLinkWithoutALookup() {
        assertThat(service.issue(server, "")).isInstanceOf(LoginLinkIssue.NotLinked.class);
        verifyNoInteractions(minecraftStaffService, tokenRepository);
    }

    private void issuedToken() {
        when(tokenRepository.consumeActive(codec.hash(TOKEN), NOW))
            .thenReturn(Optional.of(PanelLoginToken.builder().identityEmail(EMAIL).minecraftUuid(UUID).build()));
    }

    private void linkedAndAuthorized() {
        Staff staff = new Staff();
        staff.setEmail(EMAIL);
        when(minecraftStaffService.findMinecraftAssignee(server, UUID)).thenReturn(Optional.of(staff));
        when(permissionService.isAuthorizedEmail(server, EMAIL)).thenReturn(true);
    }

    private static Player player(boolean online) {
        Player player = Player.builder().build();
        player.data().setOnline(online);
        return player;
    }
}
