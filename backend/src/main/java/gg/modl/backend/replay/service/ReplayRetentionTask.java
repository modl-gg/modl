package gg.modl.backend.replay.service;

import gg.modl.backend.infrastructure.scheduling.KeysetDrainer;
import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.replay.repository.ReplayCursor;
import gg.modl.backend.replay.repository.ReplayRepository;
import gg.modl.backend.retention.service.RetentionTask;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.ReplayRetentionSettings;
import gg.modl.backend.settings.service.ReplayRetentionSettingsService;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReplayRetentionTask implements RetentionTask {
    static final int PAGE_SIZE = 200;
    private static final int UNTIL_EXHAUSTED = Integer.MAX_VALUE;

    private final ReplayRetentionSettingsService replayRetentionSettingsService;
    private final ReplayRepository replayRepository;
    private final List<ReplayHold> holds;
    private final ReplayDeletionService replayDeletionService;

    @Override
    public String subject() {
        return "replays";
    }

    @Override
    public long purge(Server server, Instant now) {
        ReplayRetentionSettings settings = replayRetentionSettingsService.getReplayRetentionSettings(server);
        if (!settings.isEnabled()) {
            return 0L;
        }
        Instant cutoff = now.minus(Duration.ofDays(settings.getDays()));
        return drain(server, cutoff, replayRepository::findExpiredWithStorageKey)
            + drain(server, cutoff, replayRepository::findExpiredWithMissingStorageKey);
    }

    private long drain(Server server, Instant cutoff, ExpiredReplayFinder finder) {
        return KeysetDrainer.drain(UNTIL_EXHAUSTED, PAGE_SIZE,
            (cursor, limit) -> finder.find(cutoff, cursor, limit),
            ReplayRetentionTask::cursorAfter,
            page -> replayDeletionService.deleteReplaysWithStorage(server, releasable(server, page)));
    }

    private List<ReplayDocument> releasable(Server server, List<ReplayDocument> page) {
        List<String> ids = page.stream().map(ReplayDocument::getId).filter(Objects::nonNull).toList();
        Set<String> held = new HashSet<>();
        for (ReplayHold hold : holds) {
            held.addAll(hold.heldReplayIds(server, ids));
        }
        return page.stream().filter(replay -> !held.contains(replay.getId())).toList();
    }

    private static ReplayCursor cursorAfter(List<ReplayDocument> page) {
        ReplayDocument last = page.getLast();
        return new ReplayCursor(last.getCreatedAt(), last.getId());
    }

    @FunctionalInterface
    private interface ExpiredReplayFinder {
        List<ReplayDocument> find(Instant cutoff, @Nullable ReplayCursor after, int limit);
    }
}
