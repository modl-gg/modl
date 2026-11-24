package gg.modl.backend.serverkey.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.scheduling.ScheduledDatabaseJobs;
import gg.modl.backend.serverkey.ServerKeyRevokedEvent;
import gg.modl.backend.serverkey.repository.ServerKeyRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ServerKeyLastSeen {
    static final Duration WRITE_INTERVAL = Duration.ofMinutes(5);
    static final int MAXIMUM_TRACKED = 512;

    private final ServerKeyRepository repository;
    private final UnitOfWork unitOfWork;
    private final ScheduledDatabaseJobs databaseJobs;
    private final Clock clock;
    private final Map<String, Instant> unwritten = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastWritten = new ConcurrentHashMap<>();

    public ServerKeyLastSeen(ServerKeyRepository repository, UnitOfWork unitOfWork, ScheduledDatabaseJobs databaseJobs,
                             Clock clock) {
        this.repository = repository;
        this.unitOfWork = unitOfWork;
        this.databaseJobs = databaseJobs;
        this.clock = clock;
    }

    public void touch(String keyId) {
        Instant now = clock.instant();
        Instant written = lastWritten.get(keyId);
        if (written != null && written.plus(WRITE_INTERVAL).isAfter(now)) {
            return;
        }
        if (unwritten.size() >= MAXIMUM_TRACKED && !unwritten.containsKey(keyId)) {
            return;
        }
        unwritten.merge(keyId, now, (first, second) -> first.isAfter(second) ? first : second);
    }

    @EventListener
    public void onRevoked(ServerKeyRevokedEvent event) {
        unwritten.remove(event.key().id());
        lastWritten.remove(event.key().id());
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    public void flush() {
        if (unwritten.isEmpty()) {
            return;
        }
        databaseJobs.run("Recording server key use", this::writePending);
    }

    void writePending() {
        Map.copyOf(unwritten).forEach((keyId, seenAt) -> {
            unitOfWork.write(() -> repository.recordSeen(keyId, seenAt, seenAt.minus(WRITE_INTERVAL)));
            unwritten.remove(keyId, seenAt);
            lastWritten.put(keyId, seenAt);
        });
        if (lastWritten.size() > MAXIMUM_TRACKED) {
            Instant stale = clock.instant().minus(WRITE_INTERVAL);
            lastWritten.values().removeIf(written -> written.isBefore(stale));
        }
    }
}
