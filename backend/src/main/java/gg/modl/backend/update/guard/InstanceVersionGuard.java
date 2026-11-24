package gg.modl.backend.update.guard;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.backup.UpgradeBackups;
import gg.modl.backend.database.backup.UpgradeBackups.UpgradeBackup;
import gg.modl.backend.database.sql.DatabaseMigration;
import gg.modl.backend.database.sql.MigrationGuard;
import gg.modl.backend.database.sql.PreMigrationState;
import gg.modl.backend.database.sql.RunningStatements;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.infrastructure.scheduling.RepeatingTasks;
import gg.modl.backend.infrastructure.util.FailureDescriptions;
import gg.modl.backend.update.ReleaseBuild;
import gg.modl.backend.update.UpdateProperties;
import gg.modl.backend.update.commit.UpdateGate;
import gg.modl.backend.update.guard.UpgradeDecision.Action;
import gg.modl.backend.update.guard.UpgradeDecision.Proceed;
import gg.modl.backend.update.guard.UpgradeDecision.Refuse;
import gg.modl.backend.update.guard.UpgradeDecision.Resume;
import gg.modl.backend.update.guard.UpgradeDecision.RollBack;
import gg.modl.backend.update.guard.UpgradeDecision.Upgrade;
import gg.modl.backend.update.install.UpdateHosts;
import gg.modl.update.EmbeddedUpdateContract;
import gg.modl.update.FailedUpdate;
import gg.modl.update.UpdateFiles;
import gg.modl.update.UpdatePhase;
import gg.modl.update.UpdateState;
import gg.modl.update.UpdateTransaction;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class InstanceVersionGuard implements MigrationGuard {
    static final Duration BACKUP_LIMIT = Duration.ofMinutes(60);
    static final Duration HEARTBEAT = Duration.ofSeconds(10);
    private static final Duration CANCEL_TIMEOUT = Duration.ofSeconds(10);

    private final UpdateFiles files;
    private final ReleaseBuild build;
    private final UpdateProperties properties;
    private final DatabaseDialect dialect;
    private final DataDirectoryProperties dataDirectory;
    private final UpgradeBackups backups;
    private final UpgradeRecovery recovery;
    private final RunningStatements runningStatements;
    private final UpdateGate gate;
    private final StartupDeadline deadline;
    private final UpdateOutcomes outcomes;
    private final UpdatePhaseReporter phases;
    private final UpdateHosts hosts;
    private final OperatorNotice notice;
    private final Clock clock;
    private @Nullable UpdateTransaction transaction;
    private @Nullable DataSource migrationConnections;

    public InstanceVersionGuard(UpdateFiles files, ReleaseBuild build, UpdateProperties properties, DatabaseDialect dialect,
                                DataDirectoryProperties dataDirectory, UpgradeBackups backups, UpgradeRecovery recovery,
                                RunningStatements runningStatements, UpdateGate gate, StartupDeadline deadline, UpdateOutcomes outcomes,
                                UpdateHosts hosts, OperatorNotice notice, Clock clock) {
        this.files = files;
        this.build = build;
        this.properties = properties;
        this.dialect = dialect;
        this.dataDirectory = dataDirectory;
        this.backups = backups;
        this.recovery = recovery;
        this.runningStatements = runningStatements;
        this.gate = gate;
        this.deadline = deadline;
        this.outcomes = outcomes;
        this.phases = hosts.reporter();
        this.hosts = hosts;
        this.notice = notice;
        this.clock = clock;
    }

    @Override
    public void beforeMigration(Flyway flyway) {
        deadline.begin();
        DataSource connections = flyway.getConfiguration().getDataSource();
        migrationConnections = connections;
        UpdateState state = readState();
        PreMigrationState database = PreMigrationState.read(connections, dialect, dataDirectory);
        Action action = UpgradeDecision.decide(new UpgradeDecision.Inputs(build.version(), build.minUpgradeFrom(), state.pending(),
            database.hasModlTables(), database.lastRunVersion(), database.databaseId(),
            schemaAboveBinary(flyway, database.schemaVersion()), properties.allowsDowngrade(), UUID.randomUUID().toString(),
            clock.instant()));
        switch (action) {
            case Proceed proceed -> log.debug("No update in progress (fresh install: {})", proceed.freshInstall());
            case Refuse refuse -> refuse(refuse.message());
            case RollBack rollBack -> rollBack(rollBack.pending(), rollBack.restore(), database);
            case Upgrade upgrade -> upgrade(upgrade.backUp(), upgrade.restoreFirst(),
                adopted(upgrade.transaction(), database.databaseId()), flyway.info().pending().length > 0);
            case Resume resume -> begin(adopted(resume.transaction(), database.databaseId()));
        }
    }

    @Override
    public void afterMigration(DatabaseMigration migration) {
        PreMigrationState.recordRunningVersion(migrationConnections(), dialect, build.versionText(), clock.instant());
        if (transaction == null) {
            return;
        }
        UpdateTransaction starting = transaction.inPhase(UpdatePhase.STARTING);
        write(starting);
        transaction = starting;
        gate.hold(starting);
        phases.report(UpdatePhase.STARTING.name());
        log.info("Migrated the database to schema version {} for modl {}", migration.schemaVersion(), build.version());
    }

    private DataSource migrationConnections() {
        DataSource connections = migrationConnections;
        if (connections == null) {
            throw new IllegalStateException("The migration data source is known only once the migration has begun");
        }
        return connections;
    }

    private UpdateState readState() {
        try {
            UpdateState state = files.readState();
            if (state.pendingAlreadyCommitted()) {
                files.deletePending();
            }
            return state;
        } catch (IOException e) {
            throw new UpgradeRefusedException(e.getMessage(), e);
        }
    }

    private void rollBack(UpdateTransaction pending, boolean restore, PreMigrationState database) {
        deadline.pause();
        if (restore) {
            recovery.restore(pending);
        } else if (database.lastRunVersion() != null) {
            PreMigrationState.recordRunningVersion(migrationConnections(), dialect, build.versionText(), clock.instant());
        }
        try {
            files.writeLastFailure(new FailedUpdate(pending.from(), pending.to(), clock.instant(), pending.failure(), restore));
            files.deletePending();
        } catch (IOException e) {
            throw new UpgradeRefusedException("The rolled back update could not be recorded (" + e.getMessage() + ")", e);
        }
        outcomes.rolledBack(pending, restore);
        notice.publish(restore
            ? "The update from modl {} to {} did not finish, so modl returned to {} and restored the database from the backup made"
                + " before it."
            : "The update from modl {} to {} did not finish, so modl returned to {}. The database was not changed.",
            pending.from(), pending.to(), pending.from());
        deadline.resume();
    }

    private void upgrade(boolean takesBackup, boolean restoresFirst, UpdateTransaction transactionToRun, boolean schemaChanges) {
        UpdateTransaction current = transactionToRun;
        if (restoresFirst) {
            write(current);
            deadline.pause();
            recovery.restore(current);
            deadline.resume();
        }
        if (takesBackup && properties.backsUpBeforeUpdating()) {
            current = backUp(current, schemaChanges);
        } else {
            if (takesBackup) {
                notice.publish("Updating modl from {} to {} without a pre-update backup (MODL_PRE_UPDATE_BACKUP=false). If the update"
                    + " fails, restore your own backup.", current.from(), current.to());
            }
            current = current.toBuilder()
                .schemaChanged(current.schemaChanged() == null ? schemaChanges : current.schemaChanged() || schemaChanges)
                .phase(UpdatePhase.MIGRATING)
                .build();
            write(current);
        }
        phases.report(UpdatePhase.MIGRATING.name());
        begin(current);
    }

    private UpdateTransaction backUp(UpdateTransaction staged, boolean schemaChanges) {
        write(staged.inPhase(UpdatePhase.BACKING_UP));
        phases.report(UpdatePhase.BACKING_UP.name());
        notice.publish("Backing up the database before updating modl from {} to {}", staged.from(), staged.to());
        deadline.pause();
        UpgradeBackup backup;
        try {
            backup = writeBackupWithinLimit(staged);
        } catch (IOException | RuntimeException e) {
            throw backupFailed(staged, e);
        } finally {
            deadline.resume();
        }
        UpdateTransaction migrating = staged.toBuilder()
            .backup(backup.fileName(), backup.sha256())
            .schemaChanged(schemaChanges)
            .phase(UpdatePhase.MIGRATING)
            .build();
        write(migrating);
        notice.publish("Backed up the database to {}", backup.path());
        return migrating;
    }

    private UpgradeBackup writeBackupWithinLimit(UpdateTransaction staged) throws IOException {
        ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "modl-pre-update-backup"));
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "modl-pre-update-backup-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        RepeatingTasks.atFixedRate(heartbeat, () -> phases.report(EmbeddedUpdateContract.PHASE_BACKUP_HEARTBEAT), 0,
            HEARTBEAT.toSeconds(), TimeUnit.SECONDS);
        Future<UpgradeBackup> written = worker.submit(() -> backups.write(staged.from().toString(), staged.to().toString()));
        try {
            return written.get(BACKUP_LIMIT.toMinutes(), TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            runningStatements.cancelAll(CANCEL_TIMEOUT);
            written.cancel(true);
            throw new IOException("it took longer than " + BACKUP_LIMIT.toMinutes() + " minutes", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            written.cancel(true);
            throw new IOException("it was interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException(cause);
        } finally {
            heartbeat.shutdownNow();
            worker.shutdownNow();
        }
    }

    private UpgradeRefusedException backupFailed(UpdateTransaction staged, Exception cause) {
        String message = "The backup before updating modl from " + staged.from() + " to " + staged.to() + " failed: "
            + FailureDescriptions.describe(cause) + ". Nothing was changed. Free disk space or fix " + backups.directory()
            + ", or start modl " + staged.from() + " again. MODL_PRE_UPDATE_BACKUP=false skips this backup.";
        try {
            if (staged.managed()) {
                files.writePending(staged.toBuilder().failure(message).build());
            } else {
                files.deletePending();
            }
        } catch (IOException e) {
            log.error("Could not record the failed pre-update backup", e);
        }
        notice.publish(message);
        return new UpgradeRefusedException(message, cause);
    }

    private UpdateTransaction adopted(UpdateTransaction pending, String databaseId) {
        return pending.toBuilder()
            .databaseId(databaseId)
            .managed(pending.managed() && hosts.runsDownloadedArtifact())
            .build();
    }

    private void begin(UpdateTransaction current) {
        transaction = current;
        deadline.enforce(current);
    }

    private void write(UpdateTransaction value) {
        try {
            files.writePending(value);
        } catch (IOException e) {
            throw new UpgradeRefusedException("updates/pending.json could not be written (" + e.getMessage() + "). Nothing was"
                + " changed; check that " + files.root() + " is writable.", e);
        }
    }

    private void refuse(String message) {
        notice.publish("modl cannot start: {}", message);
        throw new UpgradeRefusedException(message);
    }

    private static boolean schemaAboveBinary(Flyway flyway, @Nullable String schemaVersion) {
        if (schemaVersion == null) {
            return false;
        }
        Optional<MigrationVersion> highest = Arrays.stream(flyway.info().all())
            .filter(migration -> migration.getVersion() != null && migration.getState().isResolved())
            .map(MigrationInfo::getVersion)
            .max(Comparator.naturalOrder());
        return highest.isPresent() && MigrationVersion.fromVersion(schemaVersion).compareTo(highest.get()) > 0;
    }
}
