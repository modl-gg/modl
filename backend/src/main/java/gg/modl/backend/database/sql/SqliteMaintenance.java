package gg.modl.backend.database.sql;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.scheduling.ScheduledDatabaseJobs;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
public final class SqliteMaintenance {
    static final int PAGES_PER_UNIT = 1_000;
    static final int MAX_UNITS_PER_NIGHT = 100;

    private static final int UNITS_PER_CHECKPOINT = 10;
    private static final String FREE_PAGES = "PRAGMA freelist_count";
    private static final String VACUUM_PAGES = "PRAGMA incremental_vacuum(" + PAGES_PER_UNIT + ")";
    private static final String SHORT_CHECKPOINT_WAIT = "PRAGMA busy_timeout = 1000";

    private final Optional<DataSource> checkpoints;
    private final SqlStatements statements;
    private final InstanceFence fence;
    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;
    private final WalCheckpoints walCheckpoints;
    private final ScheduledDatabaseJobs jobs;

    SqliteMaintenance(Optional<SqliteConnections> connections, DatabaseAccess access, DSLContext dsl, UnitOfWork unitOfWork,
                      WalCheckpoints walCheckpoints, ScheduledDatabaseJobs jobs) {
        this.checkpoints = connections.map(sqlite -> access.tracker().track(sqlite.checkpoint(), ConnectionRole.MAINTENANCE));
        this.statements = access.statements();
        this.fence = access.fence();
        this.dsl = dsl;
        this.unitOfWork = unitOfWork;
        this.walCheckpoints = walCheckpoints;
        this.jobs = jobs;
    }

    @Scheduled(cron = "0 30 4 * * *")
    public void maintainNightly() {
        if (checkpoints.isEmpty()) {
            return;
        }
        jobs.run("The nightly database maintenance", this::maintain);
    }

    public void maintain() {
        checkpoints.ifPresent(connections -> {
            releaseFreePages();
            optimize();
            truncateWriteAheadLog(connections);
        });
    }

    private void releaseFreePages() {
        long free = unitOfWork.read(() -> dsl.connectionResult(connection -> statements.number(connection, FREE_PAGES)));
        for (int unit = 1; unit <= MAX_UNITS_PER_NIGHT && free > 0; unit++) {
            long remaining = unitOfWork.write(this::releaseUnit);
            if (remaining >= free) {
                return;
            }
            free = remaining;
            if (unit % UNITS_PER_CHECKPOINT == 0) {
                walCheckpoints.checkpoint();
            }
        }
    }

    private long releaseUnit() {
        return dsl.connectionResult(connection -> {
            statements.update(connection, VACUUM_PAGES);
            return statements.number(connection, FREE_PAGES);
        });
    }

    private void optimize() {
        unitOfWork.write(() -> {
            dsl.connection(connection -> statements.update(connection, "PRAGMA optimize"));
        });
    }

    private void truncateWriteAheadLog(DataSource connections) {
        fence.requireServing();
        try (Connection connection = connections.getConnection()) {
            statements.execute(connection, SHORT_CHECKPOINT_WAIT);
            if (statements.number(connection, "PRAGMA wal_checkpoint(TRUNCATE)") > 0) {
                log.debug("The write-ahead log was in use, so the nightly checkpoint could not truncate it");
            }
        } catch (SQLException e) {
            fence.requireServing();
            throw DatabaseToolException.failedTo("truncate the write-ahead log", e);
        }
    }
}
