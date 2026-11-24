package gg.modl.backend.player.service;

import gg.modl.backend.support.TestServers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.iplookup.service.AddressForwardingMonitor;
import gg.modl.backend.iplookup.service.IpLookupService;
import gg.modl.backend.player.PlayerService;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.PlayerNoteKind;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.data.punishment.PunishmentStatus;
import gg.modl.backend.player.dto.request.AcknowledgeNotificationsRequest;
import gg.modl.backend.player.dto.response.AcknowledgeResult;
import gg.modl.backend.player.dto.response.CreateNoteResult;
import gg.modl.backend.player.dto.response.PardonResult;
import gg.modl.backend.player.repository.PlayerParts;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.role.service.MinecraftIssuer;
import gg.modl.backend.role.service.MinecraftIssuerAuthorizer;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.support.MutableClock;
import gg.modl.backend.ticket.repository.TicketRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MinecraftPlayerServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final MinecraftIssuer CONSOLE = MinecraftIssuer.of(null, true);
    private static final String NORMALISED = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    @Mock
    private PlayerService playerService;

    @Mock
    private PlayerRepository playerRepository;

    @Mock
    private PunishmentRepository punishmentRepository;

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private PlayerStatusCalculator statusCalculator;

    @Mock
    private PunishmentTypeService punishmentTypeService;

    @Mock
    private PunishmentLifecycleService punishmentLifecycleService;

    @Mock
    private AccountLinkingService accountLinkingService;

    @Mock
    private IssuerNameResolver issuerNameResolver;

    @Mock
    private IpLookupService ipLookupService;

    @Mock
    private AddressForwardingMonitor forwardingMonitor;

    @Mock
    private MinecraftIssuerAuthorizer issuerAuthorizer;

    private MinecraftPlayerService minecraftPlayerService;

    @BeforeEach
    void setUp() {
        minecraftPlayerService = new MinecraftPlayerService(
            playerService,
            playerRepository,
            punishmentRepository,
            ticketRepository,
            statusCalculator,
            punishmentTypeService,
            punishmentLifecycleService,
            accountLinkingService,
            issuerNameResolver,
            ipLookupService,
            forwardingMonitor,
            issuerAuthorizer,
            new MutableClock(NOW)
        );
    }

    @Test
    void aWarningIsStoredAsItsOwnKindWithTheTextAsTyped() {
        Server server = TestServers.server("server", "admin@example.com");
        Player player = Player.builder().id("player-id").minecraftUuid(UUID.randomUUID()).build();
        when(playerRepository.findByMinecraftUuid(player.getMinecraftUuid().toString())).thenReturn(Optional.of(player));

        minecraftPlayerService.createNote(
            server, player.getMinecraftUuid().toString(), PlayerNoteKind.WARNING, "spam", "Moderator", CONSOLE);

        verify(playerRepository).addNote(eq("player-id"),
            argThat(note -> note.getText().equals("spam") && note.getKind() == PlayerNoteKind.WARNING));
    }

    @Test
    void aNoteMayStartLikeAWarningAndStaysANote() {
        Server server = TestServers.server("server", "admin@example.com");
        Player player = Player.builder().id("player-id").minecraftUuid(UUID.randomUUID()).build();
        when(playerRepository.findByMinecraftUuid(player.getMinecraftUuid().toString())).thenReturn(Optional.of(player));

        minecraftPlayerService.createNote(
            server, player.getMinecraftUuid().toString(), PlayerNoteKind.NOTE, "WARNING: spam", "Moderator", CONSOLE);

        verify(playerRepository).addNote(eq("player-id"),
            argThat(note -> note.getText().equals("WARNING: spam") && note.getKind() == PlayerNoteKind.NOTE));
    }

    @Test
    void createNoteAppendsOneNote() {
        Server server = TestServers.server("server", "admin@example.com");
        Player player = Player.builder()
            .id("player-id")
            .minecraftUuid(UUID.randomUUID())
            .build();

        when(playerRepository.findByMinecraftUuid(player.getMinecraftUuid().toString())).thenReturn(Optional.of(player));

        CreateNoteResult response = minecraftPlayerService.createNote(
            server,
            player.getMinecraftUuid().toString(),
            PlayerNoteKind.NOTE,
            "Test note",
            "Moderator",
            CONSOLE
        );

        assertEquals(new CreateNoteResult.Created("Note added"), response);
        verify(playerRepository).addNote(eq("player-id"),
            argThat(note -> note.getText().equals("Test note") && note.getDate().equals(NOW) && note.getKind() == PlayerNoteKind.NOTE));
    }

    @Test
    void acknowledgeNotificationsRemovesOnlyRequestedNotificationIds() {
        Server server = TestServers.server("server", "admin@example.com");
        UUID playerUuid = UUID.randomUUID();
        Player player = Player.builder().id("player-id").minecraftUuid(playerUuid).build();
        when(playerRepository.findByMinecraftUuid(playerUuid.toString())).thenReturn(Optional.of(player));
        when(playerRepository.acknowledgeNotifications("player-id", List.of("notif-1"))).thenReturn(1);

        AcknowledgeResult response = minecraftPlayerService.acknowledgeNotifications(
            server,
            new AcknowledgeNotificationsRequest(playerUuid.toString(), List.of("notif-1"), null)
        );

        assertEquals(new AcknowledgeResult("Acknowledged 1 notification(s)"), response);
        verify(playerRepository).acknowledgeNotifications("player-id", List.of("notif-1"));
    }

    @Test
    void getPlayerReportsLowercasesUuidBeforeQueryingTicketRepository() {
        Server server = TestServers.server("server", "admin@example.com");
        when(ticketRepository.findReportedPlayerTickets(any(), anyInt())).thenReturn(List.of());

        minecraftPlayerService.getPlayerReports(server, "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE");

        verify(ticketRepository).findReportedPlayerTickets(NORMALISED, 50);
    }

    @Test
    void disconnectLowercasesUuidBeforeQueryingPlayerRepository() {
        Server server = TestServers.server("server", "admin@example.com");

        minecraftPlayerService.disconnect(server, "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE");

        verify(playerRepository).markOffline(NORMALISED, NOW);
    }

    @Test
    void updateServerLowercasesUuidBeforeQueryingPlayerRepository() {
        Server server = TestServers.server("server", "admin@example.com");

        minecraftPlayerService.updateServer(server, "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE", "lobby");

        verify(playerRepository).updateLastServer(NORMALISED, "lobby");
    }

    @Test
    void pardonPlayerLeavesARolledBackUnstartedPunishmentAlone() {
        Server server = TestServers.server("server", "admin@example.com");
        Punishment rolledBack = new Punishment();
        rolledBack.setId("rolled-back");
        rolledBack.setTypeOrdinal(2);
        rolledBack.data().setStatus(PunishmentStatus.UNSTARTED);
        rolledBack.getModifications().add(new PunishmentModification(
            "modification", PunishmentModificationType.ROLLBACK.name(), NOW, "Admin", null, "", null, null, null));
        Player player = Player.builder().id("player-id").minecraftUuid(UUID.fromString(NORMALISED))
            .punishments(List.of(rolledBack)).build();
        when(playerService.findBestByUsername(server, "Steve", PlayerParts.PUNISHMENTS)).thenReturn(Optional.of(player));

        PardonResult result = minecraftPlayerService.pardonPlayer(server, "Steve", null, "Mod", CONSOLE, null);

        assertEquals(new PardonResult.Pardoned(false, 0, "No punishments found to pardon"), result);
        verifyNoInteractions(punishmentLifecycleService);
    }

    @Test
    void getPlayerPunishmentsLowercasesUuidBeforeQueryingPlayerRepository() {
        Server server = TestServers.server("server", "admin@example.com");
        when(playerRepository.findByMinecraftUuid(NORMALISED, PlayerParts.PUNISHMENTS))
            .thenReturn(Optional.empty());

        minecraftPlayerService.getPlayerPunishments(server, "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE", 1, 10);

        verify(playerRepository).findByMinecraftUuid(NORMALISED, PlayerParts.PUNISHMENTS);
    }
}
