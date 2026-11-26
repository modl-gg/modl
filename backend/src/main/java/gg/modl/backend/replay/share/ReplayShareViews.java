package gg.modl.backend.replay.share;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.scheduling.ScheduledDatabaseJobs;
import gg.modl.backend.replay.repository.ReplayShareRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReplayShareViews {
    static final Duration VIEWER_WINDOW = Duration.ofMinutes(10);
    static final int MAX_TRACKED_VIEWERS = 10_000;
    static final int MAX_PENDING_SHARES = 1_000;

    private final ReplayShareRepository shares;
    private final UnitOfWork unitOfWork;
    private final ScheduledDatabaseJobs databaseJobs;
    private final Clock clock;
    private final Map<Viewer, Instant> countedViewers = new HashMap<>();
    private final Map<String, PendingViews> pending = new HashMap<>();

    public ReplayShareViews(ReplayShareRepository shares, UnitOfWork unitOfWork, ScheduledDatabaseJobs databaseJobs, Clock clock) {
        this.shares = shares;
        this.unitOfWork = unitOfWork;
        this.databaseJobs = databaseJobs;
        this.clock = clock;
    }

    public synchronized void record(String shareId, String clientNetwork) {
        Instant now = clock.instant();
        Viewer viewer = new Viewer(shareId, clientNetwork);
        Instant counted = countedViewers.get(viewer);
        if (counted != null && counted.plus(VIEWER_WINDOW).isAfter(now)) {
            return;
        }
        boolean viewersFull = counted == null && countedViewers.size() >= MAX_TRACKED_VIEWERS;
        boolean sharesFull = !pending.containsKey(shareId) && pending.size() >= MAX_PENDING_SHARES;
        if (viewersFull || sharesFull) {
            return;
        }
        countedViewers.put(viewer, now);
        pending.merge(shareId, new PendingViews(1, now), PendingViews::plus);
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    public void flush() {
        Map<String, PendingViews> due = drain();
        if (due.isEmpty()) {
            return;
        }
        databaseJobs.run("Recording replay share views", () -> unitOfWork.write(() ->
            due.forEach((shareId, views) -> shares.addViews(shareId, views.count(), views.lastViewedAt()))));
    }

    private synchronized Map<String, PendingViews> drain() {
        Map<String, PendingViews> due = new TreeMap<>(pending);
        pending.clear();
        forgetExpired(clock.instant());
        return due;
    }

    private void forgetExpired(Instant now) {
        Instant oldestKept = now.minus(VIEWER_WINDOW);
        countedViewers.values().removeIf(counted -> !counted.isAfter(oldestKept));
    }

    private record Viewer(String shareId, String clientNetwork) {
    }

    private record PendingViews(long count, Instant lastViewedAt) {
        PendingViews plus(PendingViews later) {
            return new PendingViews(count + later.count, later.lastViewedAt.isAfter(lastViewedAt) ? later.lastViewedAt : lastViewedAt);
        }
    }
}
