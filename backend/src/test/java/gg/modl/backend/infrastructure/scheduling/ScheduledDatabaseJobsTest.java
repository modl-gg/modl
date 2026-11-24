package gg.modl.backend.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.database.sql.DatabaseWorkExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ScheduledDatabaseJobsTest {
    @Test
    void nothingRunsBeforeThisStartHasCommitted() {
        AtomicBoolean committed = new AtomicBoolean();
        AtomicInteger runs = new AtomicInteger();
        try (DatabaseWorkExecutor work = new DatabaseWorkExecutor(1, DatabaseWorkExecutor.JOB_THREADS)) {
            ScheduledDatabaseJobs jobs = new ScheduledDatabaseJobs(work, committed::get);

            jobs.run("A test job", runs::incrementAndGet);
            assertThat(runs).hasValue(0);

            committed.set(true);
            jobs.run("A test job", runs::incrementAndGet);
            assertThat(runs).hasValue(1);
        }
    }
}
