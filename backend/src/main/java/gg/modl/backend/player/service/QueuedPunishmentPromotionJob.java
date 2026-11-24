package gg.modl.backend.player.service;

import gg.modl.backend.infrastructure.scheduling.ScheduledDatabaseJobs;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.server.data.Server;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class QueuedPunishmentPromotionJob {
    static final Duration FIRST_LOOKBACK = Duration.ofDays(1);
    static final Duration COMMIT_OVERLAP = Duration.ofSeconds(10);

    private final PunishmentRepository punishmentRepository;
    private final PunishmentPromotion promotion;
    private final InstanceContext instanceContext;
    private final ScheduledDatabaseJobs databaseJobs;
    private final Clock clock;
    private final AtomicReference<Instant> scannedUntil = new AtomicReference<>();

    public QueuedPunishmentPromotionJob(PunishmentRepository punishmentRepository, PunishmentPromotion promotion,
                                        InstanceContext instanceContext, ScheduledDatabaseJobs databaseJobs, Clock clock) {
        this.punishmentRepository = punishmentRepository;
        this.promotion = promotion;
        this.instanceContext = instanceContext;
        this.databaseJobs = databaseJobs;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "PT15S", fixedDelayString = "PT15S")
    public void promoteBehindExpired() {
        databaseJobs.run("Starting queued punishments", () -> promoteBehindExpired(instanceContext.instance()));
    }

    int promoteBehindExpired(Server server) {
        Instant now = clock.instant();
        Instant previous = scannedUntil.get();
        Instant from = previous == null ? now.minus(FIRST_LOOKBACK) : previous.minus(COMMIT_OVERLAP);
        int promoted = 0;
        for (String playerId : punishmentRepository.findOnlinePlayersQueuedBehindExpired(from, now)) {
            promoted += promotion.promote(server, playerId).size();
        }
        scannedUntil.set(now);
        return promoted;
    }
}
