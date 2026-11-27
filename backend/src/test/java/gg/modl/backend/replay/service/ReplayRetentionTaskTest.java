package gg.modl.backend.replay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.replay.repository.ReplayCursor;
import gg.modl.backend.replay.repository.ReplayRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.ReplayRetentionSettings;
import gg.modl.backend.settings.service.ReplayRetentionSettingsService;
import gg.modl.backend.storage.StorageLimitPolicy;
import gg.modl.backend.storage.UploadTarget;
import gg.modl.backend.support.TestServers;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReplayRetentionTaskTest {
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final Instant CUTOFF = NOW.minus(Duration.ofDays(10));

    private final Server server = TestServers.server("server", "admin@example.com");
    private ReplayRetentionSettingsService settingsService;
    private ReplayRepository replayRepository;
    private ReplayDeletionService deletionService;
    private ReplayRetentionTask task;

    @BeforeEach
    void setUp() {
        settingsService = mock(ReplayRetentionSettingsService.class);
        replayRepository = mock(ReplayRepository.class);
        deletionService = mock(ReplayDeletionService.class);
        ReplayHold openTickets = (target, ids) -> ids.contains("attached") ? Set.of("attached") : Set.of();
        ReplayHold activePunishments = (target, ids) -> ids.contains("evidence") ? Set.of("evidence") : Set.of();
        task = new ReplayRetentionTask(settingsService, replayRepository, List.of(openTickets, activePunishments), deletionService);
    }

    @Test
    void anUploadIsCalledAbandonedOnlyLongAfterItsReservationCouldBeConfirmed() {
        assertThat(ReplayRetentionTask.ABANDONED_UPLOAD_AGE)
            .isGreaterThan(UploadTarget.VALIDITY.plus(StorageLimitPolicy.CONFIRMATION_GRACE).multipliedBy(10));
    }

    @Test
    void disabledRetentionKeepsEveryReplayButAbandonedUploads() {
        when(settingsService.getReplayRetentionSettings(server)).thenReturn(new ReplayRetentionSettings(false, 10));
        ReplayDocument abandoned = replay("abandoned");
        when(replayRepository.findAbandonedUploads(eq(NOW.minus(ReplayRetentionTask.ABANDONED_UPLOAD_AGE)), isNull(), anyInt()))
            .thenReturn(List.of(abandoned));
        when(deletionService.deleteReplaysWithStorage(eq(server), any())).thenReturn(1L);

        assertThat(task.purge(server, NOW)).isEqualTo(1);
        verify(deletionService).deleteReplaysWithStorage(server, List.of(abandoned));
        verify(replayRepository, never()).findExpiredWithStorageKey(any(), any(), anyInt());
        verify(replayRepository, never()).findExpiredWithMissingStorageKey(any(), any(), anyInt());
    }

    @Test
    void expiredReplaysAreDeletedExceptThoseAHoldKeeps() {
        when(settingsService.getReplayRetentionSettings(server)).thenReturn(new ReplayRetentionSettings(true, 10));
        ReplayDocument stored = replay("stored");
        ReplayDocument attached = replay("attached");
        ReplayDocument evidence = replay("evidence");
        ReplayDocument withoutFile = replay("without-file");
        when(replayRepository.findExpiredWithStorageKey(eq(CUTOFF), isNull(), anyInt()))
            .thenReturn(List.of(stored, attached, evidence));
        when(replayRepository.findExpiredWithMissingStorageKey(eq(CUTOFF), isNull(), anyInt())).thenReturn(List.of(withoutFile));
        when(deletionService.deleteReplaysWithStorage(eq(server), any())).thenReturn(1L);

        assertThat(task.purge(server, NOW)).isEqualTo(2);
        verify(deletionService).deleteReplaysWithStorage(server, List.of(stored));
        verify(deletionService).deleteReplaysWithStorage(server, List.of(withoutFile));
    }

    @Test
    void moreHeldReplaysThanTwentyThousandDoNotStarveTheOnesBehindThem() {
        when(settingsService.getReplayRetentionSettings(server)).thenReturn(new ReplayRetentionSettings(true, 10));
        List<ReplayDocument> held = IntStream.range(0, 24_000).mapToObj(index -> replay("%05d".formatted(index))).toList();
        ReplayDocument free = replay("free");
        Set<String> heldIds = new HashSet<>();
        held.forEach(replay -> heldIds.add(replay.getId()));
        when(replayRepository.findExpiredWithStorageKey(eq(CUTOFF), any(), anyInt())).thenAnswer(invocation -> {
            ReplayCursor cursor = invocation.getArgument(1);
            int limit = invocation.getArgument(2);
            int from = cursor == null ? 0 : Integer.parseInt(cursor.id()) + 1;
            return from < held.size() ? held.subList(from, Math.min(from + limit, held.size())) : List.of(free);
        });
        when(deletionService.deleteReplaysWithStorage(eq(server), any()))
            .thenAnswer(invocation -> (long) invocation.<List<ReplayDocument>>getArgument(1).size());
        ReplayHold everythingHeld = (target, ids) -> {
            Set<String> matching = new HashSet<>(ids);
            matching.retainAll(heldIds);
            return matching;
        };

        long purged = new ReplayRetentionTask(settingsService, replayRepository, List.of(everythingHeld), deletionService)
            .purge(server, NOW);

        assertThat(purged).isEqualTo(1);
        verify(deletionService).deleteReplaysWithStorage(server, List.of(free));
    }

    private static ReplayDocument replay(String id) {
        ReplayDocument replay = new ReplayDocument();
        replay.setId(id);
        replay.setCreatedAt(NOW.minus(Duration.ofDays(20)));
        return replay;
    }
}
