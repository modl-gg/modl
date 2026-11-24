package gg.modl.backend.infrastructure.scheduling;

import gg.modl.backend.database.DatabaseUnavailableException;
import gg.modl.backend.database.sql.DatabaseWorkExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduledDatabaseJobs {
    private final DatabaseWorkExecutor work;
    private final InstanceReadiness readiness;

    public void run(String description, Runnable job) {
        if (!readiness.committed()) {
            log.debug("{} skipped until this start has committed", description);
            return;
        }
        try {
            work.runJob(job);
        } catch (DatabaseUnavailableException e) {
            log.debug("{} skipped while the database is unavailable", description);
        } catch (RuntimeException e) {
            log.warn("{} failed", description, e);
        }
    }
}
