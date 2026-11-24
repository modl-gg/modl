package gg.modl.backend.databasemove.service;

import gg.modl.backend.database.EntityIds;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.WritePause;
import gg.modl.backend.database.WritePause.PausedWrites;
import gg.modl.backend.database.backup.DatabaseCopy;
import gg.modl.backend.database.backup.RowCountMismatch;
import gg.modl.backend.database.backup.TransferReport;
import gg.modl.backend.database.selection.DatabaseConnection;
import gg.modl.backend.database.selection.DatabaseSelection;
import gg.modl.backend.database.selection.DatabaseSelection.StartReport;
import gg.modl.backend.database.selection.FailedMove;
import gg.modl.backend.database.selection.MoveFailure;
import gg.modl.backend.database.sql.CopyTarget;
import gg.modl.backend.database.sql.DatabaseDeparture;
import gg.modl.backend.databasemove.DatabaseMoves;
import gg.modl.backend.databasemove.repository.DatabaseDepartureRepository;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ServiceUnavailableException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.infrastructure.util.FailureDescriptions;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.instance.PanelLocations;
import gg.modl.backend.log.service.AuditActor;
import gg.modl.backend.log.service.LogService;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.migration.service.ImportExclusion;
import gg.modl.backend.migration.service.RunningImports;
import gg.modl.backend.realtime.publish.RealtimeEventPublisher;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.OldCopyRemovals;
import gg.modl.backend.storage.StorageMoves;
import gg.modl.backend.update.commit.InstanceCommittedEvent;
import gg.modl.backend.update.install.InstanceRestart;
import gg.modl.proto.modl.v1.PanelResource;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class DatabaseMoveService implements DatabaseMoves {
    static final int REPORTED_MISMATCHES = 3;

    static final Duration WRITE_DRAIN_BUDGET = Duration.ofSeconds(30);
    static final Duration WORKER_STOP_BUDGET = Duration.ofSeconds(10);
    static final Duration TEST_BUDGET = Duration.ofSeconds(60);

    private final DatabaseSelection selection;
    private final DatabaseTargets targets;
    private final DatabaseCopy copy;
    private final DatabaseDepartureRepository departures;
    private final UnitOfWork unitOfWork;
    private final WritePause writes;
    private final InstanceRestart restart;
    private final ImportExclusion importExclusion;
    private final RunningImports imports;
    private final StorageMoves storageMoves;
    private final OldCopyRemovals oldCopyRemovals;
    private final PanelActionAuditor auditor;
    private final LogService logService;
    private final InstanceContext instance;
    private final RealtimeEventPublisher realtime;
    private final OperatorNotice notice;
    private final Clock clock;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(DatabaseMoveService::workerThread);
    private @Nullable DatabaseMove current;
    private @Nullable OpenedTarget handedOver;
    private boolean stopping;

    public DatabaseMoveService(DatabaseSelection selection, DatabaseTargets targets, DatabaseCopy copy,
                               DatabaseDepartureRepository departures, UnitOfWork unitOfWork, WritePause writes,
                               InstanceRestart restart, ImportExclusion importExclusion, RunningImports imports,
                               StorageMoves storageMoves, OldCopyRemovals oldCopyRemovals, PanelActionAuditor auditor,
                               LogService logService, InstanceContext instance, RealtimeEventPublisher realtime, OperatorNotice notice,
                               Clock clock) {
        this.selection = selection;
        this.targets = targets;
        this.copy = copy;
        this.departures = departures;
        this.unitOfWork = unitOfWork;
        this.writes = writes;
        this.restart = restart;
        this.importExclusion = importExclusion;
        this.imports = imports;
        this.storageMoves = storageMoves;
        this.oldCopyRemovals = oldCopyRemovals;
        this.auditor = auditor;
        this.logService = logService;
        this.instance = instance;
        this.realtime = realtime;
        this.notice = notice;
        this.clock = clock;
    }

    public DatabaseSettingsView view() {
        boolean managed = selection.managed();
        return new DatabaseSettingsView(selection.currentLocation(), selection.currentUsername(), selection.currentUsesTls(), managed,
            restart.mode(), managed ? Optional.empty() : move());
    }

    @Override
    public synchronized boolean isMovingDatabase() {
        return current != null && current.active();
    }

    public DatabaseTestResult test(DatabaseConnection target) {
        requireUnmanaged();
        synchronized (this) {
            requireNoActiveMove();
        }
        Future<DatabaseTestResult> tested;
        try {
            tested = worker.submit(() -> {
                try (OpenedTarget opened = targets.assess(target)) {
                    return opened.result();
                }
            });
        } catch (RejectedExecutionException e) {
            throw new ServiceUnavailableException("modl is stopping, so the database cannot be tested", e);
        }
        try {
            return tested.get(TEST_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException runtime ? runtime
                : new ServiceUnavailableException("The database could not be tested", e);
        } catch (TimeoutException e) {
            tested.cancel(true);
            throw new ServiceUnavailableException("The database test did not finish within " + TEST_BUDGET.toSeconds() + " seconds", e);
        } catch (CancellationException e) {
            throw new ServiceUnavailableException("modl is stopping, so the database test was cancelled", e);
        } catch (InterruptedException e) {
            tested.cancel(true);
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("Interrupted while testing the database");
        }
    }

    public DatabaseSettingsView move(Server server, String actorEmail, DatabaseConnection target, boolean manualRestartAcknowledged) {
        requireUnmanaged();
        if (restart.mode() == InstanceRestart.Mode.MANUAL && !manualRestartAcknowledged) {
            throw new ValidationException("Confirm that you will restart modl yourself to finish the move",
                DatabaseMoveCodes.RESTART_NOT_ACKNOWLEDGED);
        }
        importExclusion.exclusively(() -> begin(server, actorEmail, target));
        return view();
    }

    private DatabaseMove begin(Server server, String actorEmail, DatabaseConnection target) {
        Reservation reserved = reserve(target);
        try {
            auditor.recordStaffAction(server, actorEmail, "Started moving the database to " + target.dialect().displayName());
            worker.execute(() -> run(server, actorEmail, target));
            return reserved.started();
        } catch (RejectedExecutionException e) {
            release(reserved);
            throw new ServiceUnavailableException("modl is stopping, so the database cannot be moved", e);
        } catch (RuntimeException e) {
            release(reserved);
            throw e;
        }
    }

    private synchronized Reservation reserve(DatabaseConnection target) {
        requireNoActiveMove();
        if (target.location().equals(selection.currentLocation())) {
            throw new ConflictException("modl already uses this database", DatabaseMoveCodes.SAME);
        }
        blocker().ifPresent(failure -> {
            throw new ConflictException(failure.message(), failure.code());
        });
        Reservation reserved = new Reservation(DatabaseMove.started(target.location(), clock.instant()), current);
        current = reserved.started();
        return reserved;
    }

    private synchronized void release(Reservation reserved) {
        if (current == reserved.started()) {
            current = reserved.previous();
        }
    }

    private record Reservation(DatabaseMove started, @Nullable DatabaseMove previous) {
    }

    @EventListener(InstanceCommittedEvent.class)
    public void reportStart() {
        Optional<StartReport> report;
        synchronized (this) {
            if (current != null) {
                return;
            }
            report = selection.settleStart();
        }
        report.ifPresent(this::announce);
    }

    private void announce(StartReport report) {
        switch (report) {
            case StartReport.Arrived arrived -> {
                recordSystemAction("Moved the database to " + arrived.now().dialect().displayName());
                notice.publish("modl now uses {}. The previous database was kept unchanged.", arrived.now().describe());
            }
            case StartReport.FellBack fellBack -> recordSystemAction("Moving the database to "
                + fellBack.abandoned().dialect().displayName() + " did not finish (" + fellBack.reason() + "); modl kept using "
                + fellBack.kept().dialect().displayName());
        }
    }

    @PreDestroy
    void stop() {
        synchronized (this) {
            stopping = true;
        }
        for (Runnable queued : worker.shutdownNow()) {
            if (queued instanceof Future<?> waiting) {
                waiting.cancel(false);
            }
        }
        try {
            if (!worker.awaitTermination(WORKER_STOP_BUDGET.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("The database move did not stop within {} seconds; it can no longer change the database choice",
                    WORKER_STOP_BUDGET.toSeconds());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        OpenedTarget held;
        synchronized (this) {
            held = handedOver;
            handedOver = null;
        }
        if (held != null) {
            held.close();
        }
    }

    private synchronized void recordChoice(Runnable write) {
        if (stopping) {
            throw new IllegalStateException("modl is stopping, so the database move no longer changes the database choice");
        }
        write.run();
    }

    private synchronized Optional<DatabaseMove> move() {
        if (current != null) {
            return Optional.of(current);
        }
        FailedMove failed = selection.choice().failure();
        return failed == null ? Optional.empty() : Optional.of(DatabaseMove.failedEarlier(failed.target(), failed.failure()));
    }

    private void run(Server server, String actorEmail, DatabaseConnection target) {
        MoveAttempt attempt = new MoveAttempt(target, EntityIds.next());
        try {
            Optional<MoveFailure> failure = attempt.copyAndRecord();
            if (failure.isPresent()) {
                attempt.abandon(server, actorEmail, failure.get());
                return;
            }
            attempt.handOver();
            restartOnto(target);
        } catch (RuntimeException | Error e) {
            log.error("Moving the database to {} stopped unexpectedly", target.describe(), e);
            if (!attempt.recorded()) {
                attempt.abandon(server, actorEmail, new MoveFailure(DatabaseMoveCodes.COPY_FAILED, "The move stopped unexpectedly",
                    FailureDescriptions.describe(e)));
            }
            if (e instanceof Error error) {
                throw error;
            }
        } finally {
            attempt.closeUnlessHandedOver();
        }
    }

    private final class MoveAttempt {
        private final DatabaseConnection target;
        private final String token;
        private @Nullable OpenedTarget opened;
        private @Nullable PausedWrites paused;
        private boolean marked;
        private boolean recording;
        private boolean recorded;
        private boolean handedOverTarget;

        MoveAttempt(DatabaseConnection target, String token) {
            this.target = target;
            this.token = token;
        }

        Optional<MoveFailure> copyAndRecord() {
            Optional<MoveFailure> notCleared = clearStaleDeparture();
            if (notCleared.isPresent()) {
                return notCleared;
            }
            OpenedTarget assessed = targets.assess(target);
            opened = assessed;
            Optional<CopyTarget> ready = assessed.target();
            if (ready.isEmpty()) {
                DatabaseTestResult refused = assessed.result();
                return Optional.of(new MoveFailure(refused.code(), refused.message(), refused.detail()));
            }
            recordChoice(() -> selection.recordCopying(target.location(), token));
            advance(DatabaseMovePhase.PAUSING_WRITES);
            Optional<MoveFailure> notPaused = importExclusion.exclusively(this::pauseWrites);
            if (notPaused.isPresent()) {
                return notPaused;
            }
            Optional<MoveFailure> notMarked = markDeparture();
            if (notMarked.isPresent()) {
                return notMarked;
            }
            advance(DatabaseMovePhase.COPYING);
            Optional<MoveFailure> notCopied = copyInto(ready.get());
            if (notCopied.isPresent()) {
                return notCopied;
            }
            return record();
        }

        private Optional<MoveFailure> clearStaleDeparture() {
            try {
                unitOfWork.write(departures::clear);
                return Optional.empty();
            } catch (RuntimeException e) {
                log.warn("The mark of an earlier database move could not be removed", e);
                return Optional.of(new MoveFailure(DatabaseMoveCodes.FAILED, "The current database could not be prepared for the move",
                    FailureDescriptions.describe(e)));
            }
        }

        private Optional<MoveFailure> pauseWrites() {
            Optional<MoveFailure> blocked = blocker();
            if (blocked.isPresent()) {
                return blocked;
            }
            Optional<PausedWrites> drained = writes.pauseAndDrain(WRITE_DRAIN_BUDGET);
            if (drained.isEmpty()) {
                return Optional.of(new MoveFailure(DatabaseMoveCodes.WRITES_BUSY, "Changes still running did not finish in time",
                    WRITE_DRAIN_BUDGET.toSeconds() + " seconds"));
            }
            paused = drained.get();
            return Optional.empty();
        }

        private Optional<MoveFailure> markDeparture() {
            PausedWrites admitting = paused;
            if (admitting == null) {
                throw new IllegalStateException("The current database is marked only while writes are paused");
            }
            try {
                admitting.admit(() -> unitOfWork.write(() -> departures.mark(new DatabaseDeparture(token, target.describe(),
                    clock.instant()))));
                marked = true;
                return Optional.empty();
            } catch (RuntimeException e) {
                log.warn("The current database could not be marked as moved to {}", target.describe(), e);
                return Optional.of(new MoveFailure(DatabaseMoveCodes.COPY_FAILED, "The current database could not be marked as moved",
                    FailureDescriptions.describe(e)));
            }
        }

        private Optional<MoveFailure> copyInto(CopyTarget ready) {
            try {
                TransferReport report = copy.into(ready.target());
                copied(report);
                return verify(report.mismatchesWith(ready.countRowsByTable()));
            } catch (IOException | RuntimeException e) {
                log.warn("Copying the database to {} failed", target.describe(), e);
                return Optional.of(new MoveFailure(DatabaseMoveCodes.COPY_FAILED, "The copy failed", FailureDescriptions.describe(e)));
            }
        }

        private Optional<MoveFailure> record() {
            try {
                recording = true;
                recordChoice(() -> selection.recordMoved(target));
                recorded = true;
                return Optional.empty();
            } catch (RuntimeException e) {
                log.warn("The new database could not be recorded in {}", selection.choiceFile(), e);
                return Optional.of(new MoveFailure(DatabaseMoveCodes.COPY_FAILED, "The new database could not be recorded",
                    FailureDescriptions.describe(e)));
            }
        }

        boolean recorded() {
            return recorded;
        }

        void handOver() {
            synchronized (DatabaseMoveService.this) {
                handedOver = opened;
            }
            handedOverTarget = true;
        }

        void abandon(Server server, String actorEmail, MoveFailure failure) {
            if (recording && !copyForgotten()) {
                handOver();
                waitForOperator(target);
                return;
            }
            if (paused != null) {
                paused.resume();
            }
            if (marked) {
                clearDeparture();
            }
            settleFailure(server, actorEmail, target, failure);
        }

        private boolean copyForgotten() {
            OpenedTarget held = opened;
            Optional<CopyTarget> copied = held == null ? Optional.empty() : held.target();
            if (copied.isEmpty()) {
                return true;
            }
            try {
                copied.get().clearDeparture();
                return true;
            } catch (RuntimeException e) {
                log.warn("The copy in {} could not be told apart from a finished move, so changes stay paused", target.describe(), e);
                return false;
            }
        }

        void closeUnlessHandedOver() {
            if (!handedOverTarget && opened != null) {
                opened.close();
            }
        }

        private void clearDeparture() {
            try {
                unitOfWork.write(departures::clear);
            } catch (RuntimeException e) {
                log.warn("The mark of the stopped move could not be removed; the next start removes it", e);
            }
        }
    }

    static Optional<MoveFailure> verify(List<RowCountMismatch> mismatches) {
        if (mismatches.isEmpty()) {
            return Optional.empty();
        }
        String shown = String.join(", ", mismatches.stream().limit(REPORTED_MISMATCHES).map(RowCountMismatch::describe).toList());
        int more = mismatches.size() - REPORTED_MISMATCHES;
        return Optional.of(new MoveFailure(DatabaseMoveCodes.COPY_FAILED, "The copy does not hold every row",
            more > 0 ? shown + " and " + more + " more tables" : shown));
    }

    private void restartOnto(DatabaseConnection target) {
        switch (restart.mode()) {
            case IN_PLACE -> restartAutomatically(target, "modl copied its data to {} and restarts on it now.");
            case EXIT -> restartAutomatically(target,
                "modl copied its data to {} and exits now; its container restart policy starts it again on the new database.");
            case MANUAL -> waitForOperator(target);
        }
    }

    private void restartAutomatically(DatabaseConnection target, String announcement) {
        advance(DatabaseMovePhase.RESTARTING);
        notice.publish(announcement, target.describe());
        try {
            restart.restart();
        } catch (RuntimeException e) {
            log.warn("modl could not restart itself onto the new database", e);
            waitForOperator(target);
        }
    }

    private void waitForOperator(DatabaseConnection target) {
        advance(DatabaseMovePhase.WAITING_FOR_RESTART);
        notice.publish("modl copied its data to {}. Restart modl to finish the move; changes are paused until then.",
            target.describe());
    }

    private void settleFailure(Server server, String actorEmail, DatabaseConnection target, MoveFailure failure) {
        synchronized (this) {
            DatabaseMove running = current;
            current = running == null ? DatabaseMove.started(target.location(), clock.instant()).failed(failure, clock.instant())
                : running.failed(failure, clock.instant());
        }
        changed();
        try {
            recordChoice(() -> selection.recordFailure(target.location(), failure));
        } catch (RuntimeException e) {
            log.warn("The failed database move could not be recorded", e);
        }
        notice.publish("Moving the database to {} stopped: {}. modl keeps using {}. Open {} to try again.", target.describe(),
            failure.describe(), selection.choice().selected().describe(), PanelLocations.DATABASE);
        try {
            auditor.recordStaffAction(server, actorEmail, "Moving the database to " + target.dialect().displayName() + " failed: "
                + failure.message());
        } catch (RuntimeException e) {
            log.warn("The failed database move could not be added to the audit log", e);
        }
    }

    private Optional<MoveFailure> blocker() {
        if (imports.any()) {
            return Optional.of(new MoveFailure(DatabaseMoveCodes.IMPORT_RUNNING,
                "Wait until the import finishes before moving the database", ""));
        }
        if (storageMoves.isMovingFiles() || oldCopyRemovals.isRemovingOldCopies()) {
            return Optional.of(new MoveFailure(DatabaseMoveCodes.STORAGE_MOVING,
                "Wait until the uploaded files are moved before moving the database", ""));
        }
        return Optional.empty();
    }

    private void advance(DatabaseMovePhase phase) {
        synchronized (this) {
            if (current == null) {
                return;
            }
            current = current.at(phase, clock.instant());
        }
        changed();
    }

    private void copied(TransferReport report) {
        synchronized (this) {
            if (current == null) {
                return;
            }
            current = current.copied(report, clock.instant());
        }
        changed();
    }

    private void changed() {
        try {
            realtime.invalidatePanel(instance.instance(), PanelResource.PANEL_RESOURCE_SETTINGS);
        } catch (RuntimeException e) {
            log.debug("The database move change could not be announced to the panel", e);
        }
    }

    private void recordSystemAction(String description) {
        try {
            logService.recordStaffAction(instance.instance(), AuditActor.system(), description);
        } catch (RuntimeException e) {
            log.warn("Could not add the database move to the audit log", e);
        }
    }

    private void requireNoActiveMove() {
        if (current != null && current.active()) {
            throw new ConflictException("The database is already being moved", DatabaseMoveCodes.MOVE_RUNNING);
        }
    }

    private void requireUnmanaged() {
        if (selection.managed()) {
            throw new ConflictException("The database is set by MODL_DATABASE_URL or connection.yml and cannot be changed from the"
                + " panel", DatabaseMoveCodes.MANAGED);
        }
    }

    private static Thread workerThread(Runnable task) {
        Thread thread = new Thread(task, "modl-database-move");
        thread.setDaemon(true);
        return thread;
    }
}
