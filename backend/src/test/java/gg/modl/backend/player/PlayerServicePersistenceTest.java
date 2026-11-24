package gg.modl.backend.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.EntityIds;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.player.data.NoteEntry;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.PlayerNoteKind;
import gg.modl.backend.player.repository.PlayerParts;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.service.PlayerStatusCalculator;
import gg.modl.backend.player.service.PunishmentQueryService;
import gg.modl.backend.realtime.publish.RealtimeEventPublisher;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.MutableClock;
import gg.modl.proto.modl.v1.PanelResource;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlayerServicePersistenceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID PLAYER_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private PlayerRepository playerRepository;

    @Mock
    private PlayerStatusCalculator statusCalculator;

    @Mock
    private RealtimeEventPublisher realtimePublisher;

    @Mock
    private PunishmentQueryService punishmentQueryService;

    @Mock
    private Server server;

    private PlayerService playerService;

    @BeforeEach
    void setUp() {
        playerService = new PlayerService(
            playerRepository, statusCalculator, realtimePublisher, punishmentQueryService, new MutableClock(NOW));
    }

    @Test
    void createPlayerAssignsAnEntityIdAndAnnouncesOnlyAfterTheInsert() {
        when(playerRepository.insertIfAbsent(any())).thenReturn(true);

        Player created = playerService.createPlayer(server, PLAYER_UUID, "ApiCreatedPlayer");

        assertEquals(EntityIds.LENGTH, created.getId().length());
        assertEquals("ApiCreatedPlayer", created.getUsernames().getFirst().username());
        assertEquals(NOW, created.getUsernames().getFirst().date());
        InOrder order = inOrder(playerRepository, realtimePublisher);
        order.verify(playerRepository).insertIfAbsent(created);
        order.verify(realtimePublisher)
            .invalidatePanel(server, PanelResource.PANEL_RESOURCE_PLAYERS, PLAYER_UUID.toString());
    }

    @Test
    void createPlayerConflictsWhenTheUuidExistsAndAnnouncesNothing() {
        when(playerRepository.insertIfAbsent(any())).thenReturn(false);

        assertThrows(ConflictException.class, () -> playerService.createPlayer(server, PLAYER_UUID, "Duplicate"));

        verifyNoInteractions(realtimePublisher);
    }

    @Test
    void aPanelNoteIsAlwaysANoteWhateverItsText() {
        Player player = Player.builder().id("player-id").minecraftUuid(PLAYER_UUID).build();
        when(playerRepository.findByMinecraftUuid(PLAYER_UUID.toString())).thenReturn(Optional.of(player));
        when(playerRepository.findByMinecraftUuid(PLAYER_UUID.toString(), PlayerParts.NOTES)).thenReturn(Optional.of(player));

        playerService.addNote(server, PLAYER_UUID, "WARNING: spam", "Moderator", null);

        ArgumentCaptor<NoteEntry> note = ArgumentCaptor.forClass(NoteEntry.class);
        verify(playerRepository).addNote(eq("player-id"), note.capture());
        assertEquals("WARNING: spam", note.getValue().getText());
        assertEquals(PlayerNoteKind.NOTE, note.getValue().getKind());
    }

    @Test
    void addNoteAppendsByTheInternalId() {
        Player player = Player.builder().id("player-id").minecraftUuid(PLAYER_UUID).build();
        when(playerRepository.findByMinecraftUuid(PLAYER_UUID.toString())).thenReturn(Optional.of(player));
        when(playerRepository.findByMinecraftUuid(PLAYER_UUID.toString(), PlayerParts.NOTES)).thenReturn(Optional.of(player));

        playerService.addNote(server, PLAYER_UUID, "watch", "Moderator", null);

        ArgumentCaptor<NoteEntry> note = ArgumentCaptor.forClass(NoteEntry.class);
        verify(playerRepository).addNote(eq("player-id"), note.capture());
        assertEquals("watch", note.getValue().getText());
        assertEquals(NOW, note.getValue().getDate());
    }

    @Test
    void addIpRecordsTheSightingAtTheClockTime() {
        Player player = Player.builder().id("player-id").minecraftUuid(PLAYER_UUID).build();
        when(playerRepository.findByMinecraftUuid(PLAYER_UUID.toString())).thenReturn(Optional.of(player));
        when(playerRepository.findByMinecraftUuid(PLAYER_UUID.toString(), PlayerParts.IPS)).thenReturn(Optional.of(player));

        playerService.addIp(server, PLAYER_UUID, "203.0.113.7");

        verify(playerRepository).recordIp("player-id", "203.0.113.7", NOW);
    }

    @Test
    void addUsernameOfAnUnknownPlayerIsNotFound() {
        when(playerRepository.findByMinecraftUuid(PLAYER_UUID.toString())).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> playerService.addUsername(server, PLAYER_UUID, "NewName"));
    }
}
