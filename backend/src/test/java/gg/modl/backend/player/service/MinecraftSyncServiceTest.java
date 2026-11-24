package gg.modl.backend.player.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.player.PunishmentStore;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.dto.response.SyncResult;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.player.service.MinecraftSyncService.ChatLogInput;
import gg.modl.backend.player.service.MinecraftSyncService.CommandLogInput;
import gg.modl.backend.player.service.MinecraftSyncService.OnlinePlayerInput;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.support.TestServers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MinecraftSyncServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final Server server = TestServers.server("server", "admin@example.com");
    private final DirectUnitOfWork unitOfWork = new DirectUnitOfWork();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final PunishmentRepository punishments = mock(PunishmentRepository.class);
    private final MinecraftChatLogService chatLogs = mock(MinecraftChatLogService.class);
    private final PublicUrls publicUrls = mock(PublicUrls.class);
    private final PunishmentTypesFixture types = new PunishmentTypesFixture();
    private MinecraftSyncService service;

    @BeforeEach
    void setUp() {
        service = new MinecraftSyncService(
            unitOfWork,
            clock,
            players,
            punishments,
            mock(StaffRepository.class),
            new PlayerStatusCalculator(types.types(), types.thresholds(), clock),
            types.types(),
            mock(PunishmentLifecycleService.class),
            chatLogs,
            mock(IssuerNameResolver.class),
            mock(SyncStaffEventService.class),
            mock(SyncActiveStaffService.class),
            publicUrls
        );
    }

    @Test
    void syncReturnsTheEnvelopeWithoutAWriteWhenNothingChanges() {
        when(publicUrls.panel()).thenReturn("https://panel.example.com");

        SyncResult response = service.sync(server, "2025-01-01T00:00:00Z", List.of(), "lobby", List.of(), List.of());

        assertThat(response.timestamp()).isEqualTo(NOW.minus(MinecraftSyncService.COMMIT_OVERLAP).toString());
        assertThat(response.data().pendingPunishments()).isEmpty();
        assertThat(response.data().staffNotifications()).isNotNull();
        assertThat(response.data().panelUrl()).isEqualTo("https://panel.example.com");
        assertThat(unitOfWork.committedWrites()).isZero();
    }

    @SuppressWarnings("unchecked")
    @Test
    void syncLowercasesOnlineUuids() {
        service.sync(server, null,
            List.of(new OnlinePlayerInput("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE", "PlayerOne", "1.2.3.4")),
            "lobby", List.of(), List.of());

        ArgumentCaptor<Collection<String>> uuids = ArgumentCaptor.forClass(Collection.class);
        verify(players).findByMinecraftUuids(uuids.capture());
        assertThat(uuids.getValue()).containsExactly("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    }

    @Test
    void presenceWritesOnlyTheDifference() {
        String stays = UUID.randomUUID().toString();
        String left = UUID.randomUUID().toString();
        when(players.findOnlineUuids("lobby")).thenReturn(Set.of(stays, left));

        service.applyPresence(server, List.of(new OnlinePlayerInput(stays, "Stays", null)), "lobby");

        verify(players).markOfflineAll(Set.of(left), NOW);
        assertThat(unitOfWork.committedWrites()).isEqualTo(1);
    }

    @Test
    void presenceWithNobodyMissingTakesNoWriter() {
        String stays = UUID.randomUUID().toString();
        when(players.findOnlineUuids(null)).thenReturn(Set.of(stays));

        service.applyPresence(server, List.of(new OnlinePlayerInput(stays, "Stays", null)), " ");

        verify(players, never()).markOfflineAll(any(), any());
        assertThat(unitOfWork.committedWrites()).isZero();
    }

    @Test
    void logsAreNotWrappedSoTheLogServiceCanCommitPerChunk() {
        doAnswer(call -> {
            assertThat(unitOfWork.inWrite()).isFalse();
            return null;
        }).when(chatLogs).submitChatLogs(any(), anyList());
        doAnswer(call -> {
            assertThat(unitOfWork.inWrite()).isFalse();
            return null;
        }).when(chatLogs).submitCommandLogs(any(), anyList());
        String uuid = UUID.randomUUID().toString();

        service.submitLogs(server,
            IntStream.range(0, 1_200).mapToObj(index -> new ChatLogInput(uuid, "Steve", "hi " + index, index, "lobby")).toList(),
            List.of(new CommandLogInput(uuid, "Steve", "/spawn", 1L, "lobby")));

        verify(chatLogs).submitChatLogs(any(), anyList());
        verify(chatLogs).submitCommandLogs(any(), anyList());
    }

    @Test
    void anUndeliveredKickThatWasRolledBackIsNotPushed() {
        UUID uuid = UUID.randomUUID();
        Player player = Player.builder().id("player0000000001").minecraftUuid(uuid).build();
        Punishment kick = PunishmentStore.draft("KICKKKKK", PunishmentStore.KICK, null, null, null);
        kick.getModifications().add(PunishmentStore.change("M1", "ROLLBACK", NOW.minusSeconds(1), null));
        when(players.findByMinecraftUuids(any())).thenReturn(List.of(player));
        when(punishments.findSyncRelevant(any(), any(), any())).thenReturn(Map.of(player.getId(), List.of(kick)));

        SyncResult response = service.sync(server, null,
            List.of(new OnlinePlayerInput(uuid.toString(), "Steve", null)), null, List.of(), List.of());

        assertThat(response.data().pendingPunishments()).isEmpty();
        assertThat(response.data().recentlyModifiedPunishments()).hasSize(1);
    }

    @Test
    void anOutOfRangeLastSyncIsClamped() {
        when(players.findByMinecraftUuids(any())).thenReturn(
            List.of(Player.builder().id("player0000000001").minecraftUuid(UUID.randomUUID()).build()));

        service.sync(server, "-100000-01-01T00:00:00Z",
            List.of(new OnlinePlayerInput(UUID.randomUUID().toString(), "Steve", null)), null, List.of(), List.of());

        verify(punishments).findSyncRelevant(List.of("player0000000001"), Instant.EPOCH, NOW);
    }
}
