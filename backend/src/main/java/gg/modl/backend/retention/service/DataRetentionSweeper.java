package gg.modl.backend.retention.service;

import gg.modl.backend.infrastructure.scheduling.ScheduledDatabaseJobs;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.server.data.Server;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataRetentionSweeper {
    private final List<RetentionTask> tasks;
    private final InstanceContext instanceContext;
    private final ScheduledDatabaseJobs databaseJobs;
    private final Clock clock;

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M")
    public void sweep() {
        databaseJobs.run("Removing expired data", () -> sweep(instanceContext.instance()));
    }

    Map<String, Long> sweep(Server server) {
        Instant now = clock.instant();
        Map<String, Long> removed = new LinkedHashMap<>();
        for (RetentionTask task : tasks) {
            try {
                long count = task.purge(server, now);
                if (count > 0) {
                    removed.put(task.subject(), count);
                }
            } catch (RuntimeException e) {
                log.warn("Removing expired {} failed", task.subject(), e);
            }
        }
        report(removed);
        return removed;
    }

    private static void report(Map<String, Long> removed) {
        if (removed.isEmpty()) {
            log.debug("Data retention found nothing to remove");
            return;
        }
        log.info("Data retention removed {}", removed.entrySet().stream()
            .map(entry -> entry.getValue() + " " + entry.getKey())
            .collect(Collectors.joining(", ")));
    }
}
