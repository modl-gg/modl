package gg.modl.backend.player.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.iplookup.service.AddressForwardingMonitor;
import gg.modl.backend.iplookup.service.IpLookupService;
import gg.modl.backend.player.PlayerService;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.UsernameEntry;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.dto.response.SimplePunishmentView;
import gg.modl.backend.player.dto.response.SyncPunishmentEntry;
import gg.modl.backend.player.dto.response.SyncResult;
import gg.modl.backend.player.repository.LoginRecord;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.player.service.MinecraftSyncService.OnlinePlayerInput;
import gg.modl.backend.role.service.MinecraftIssuerAuthorizer;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.OffenderThresholdSettingsService;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.ticket.repository.TicketRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PlayerFacingReasonDeliveryTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String PLAYER_ID = "player0000000001";
    private static final String INTERNAL = "reported by Alex";
    private static final String DESCRIPTION = "Follow the server rules";

    private final Server server = TestServers.server("server", "admin@example.com");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final PunishmentRepository punishments = mock(PunishmentRepository.class);
    private final PunishmentTypeService types = mock(PunishmentTypeService.class);
    private final PunishmentLifecycleService lifecycle = mock(PunishmentLifecycleService.class);
    private final IssuerNameResolver issuers = mock(IssuerNameResolver.class);
    private final PlayerStatusCalculator calculator =
        new PlayerStatusCalculator(types, mock(OffenderThresholdSettingsService.class), clock);

    @ParameterizedTest
    @CsvSource({"1, reported by Alex", "2, reported by Alex", "4, Follow the server rules", "30, Follow the server rules"})
    void loginSendsTheReasonMeantForThePlayer(int ordinal, String shown) {
        Punishment punishment = givenPunishment(ordinal);
        when(players.recordLogin(any())).thenReturn(new LoginRecord(PLAYER_ID, false));
        when(punishments.findEnforcementRelevant(PLAYER_ID, NOW)).thenReturn(List.of(punishment));
        MinecraftPlayerService service = new MinecraftPlayerService(mock(PlayerService.class), players, punishments,
            mock(TicketRepository.class), calculator, types, lifecycle, mock(AccountLinkingService.class), issuers,
            mock(IpLookupService.class), mock(AddressForwardingMonitor.class), mock(MinecraftIssuerAuthorizer.class), clock);

        List<SimplePunishmentView> delivered =
            service.login(server, PLAYER, "Steve", null, null, null).activePunishments();

        assertThat(delivered).singleElement().extracting(SimplePunishmentView::description).isEqualTo(shown);
        verify(players).withdrawRefusedLogin(PLAYER_ID, NOW);
    }

    @ParameterizedTest
    @CsvSource({"1, reported by Alex", "2, reported by Alex", "4, Follow the server rules", "30, Follow the server rules"})
    void syncSendsTheReasonMeantForThePlayer(int ordinal, String shown) {
        Punishment punishment = givenPunishment(ordinal);
        Player player = Player.builder().id(PLAYER_ID).minecraftUuid(PLAYER)
            .usernames(new ArrayList<>(List.of(new UsernameEntry("Steve", NOW)))).build();
        when(players.findByMinecraftUuids(any())).thenReturn(List.of(player));
        when(punishments.findSyncRelevant(any(), any(), any())).thenReturn(Map.of(PLAYER_ID, List.of(punishment)));
        MinecraftSyncService service = new MinecraftSyncService(new DirectUnitOfWork(), clock, players, punishments,
            mock(StaffRepository.class), calculator, types, lifecycle,
            mock(MinecraftChatLogService.class), issuers, mock(SyncStaffEventService.class),
            mock(SyncActiveStaffService.class), mock(PublicUrls.class));

        SyncResult result = service.sync(server, NOW.minusSeconds(120).toString(),
            List.of(new OnlinePlayerInput(PLAYER.toString(), "Steve", null)), "lobby", List.of(), List.of());

        List<SyncPunishmentEntry> sent = new ArrayList<>(result.data().pendingPunishments());
        sent.addAll(result.data().recentlyModifiedPunishments());
        assertThat(sent).hasSize(2).allSatisfy(entry -> assertThat(entry.punishment().description()).isEqualTo(shown));
    }

    private Punishment givenPunishment(int ordinal) {
        when(types.getPunishmentTypes(server)).thenReturn(List.of(PunishmentType.builder()
            .ordinal(ordinal).name("Type").category("Gameplay").playerDescription(DESCRIPTION).build()));
        when(types.getPunishmentTypeName(server, ordinal)).thenReturn("Type");
        Instant issued = NOW.minusSeconds(30);
        Punishment punishment = new Punishment("PUNISH01", ordinal, "Mod", null, PunishmentIssuerKind.STAFF, issued, issued,
            new ArrayList<>(List.of(new PunishmentModification("M1", "APPEAL_REJECT", issued, "Mod", null,
                INTERNAL, null, null, null))),
            new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
            new HashMap<>(Map.of("enforcementCategory", "BAN", "reason", INTERNAL)));
        return punishment;
    }
}
