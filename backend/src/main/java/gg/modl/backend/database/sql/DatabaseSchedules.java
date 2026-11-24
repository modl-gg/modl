package gg.modl.backend.database.sql;

import gg.modl.backend.database.ExpiredRowSweeper;
import gg.modl.backend.infrastructure.scheduling.ScheduledDatabaseJobs;
import org.springframework.scheduling.annotation.Scheduled;

public final class DatabaseSchedules {
    private final ScheduledDatabaseJobs jobs;
    private final ExpiredRowSweeper sweeper;
    private final WalCheckpoints walCheckpoints;

    public DatabaseSchedules(ScheduledDatabaseJobs jobs, ExpiredRowSweeper sweeper, WalCheckpoints walCheckpoints) {
        this.jobs = jobs;
        this.sweeper = sweeper;
        this.walCheckpoints = walCheckpoints;
    }

    @Scheduled(initialDelay = 5_000, fixedDelay = 5_000)
    public void checkpointWriteAheadLog() {
        jobs.run("The write-ahead log checkpoint", walCheckpoints::checkpoint);
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void sweepExpiredRows() {
        jobs.run("The expired-row sweep", sweeper::sweep);
    }
}
