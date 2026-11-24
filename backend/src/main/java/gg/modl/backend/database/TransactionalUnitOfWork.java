package gg.modl.backend.database;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
public final class TransactionalUnitOfWork implements UnitOfWork, InFlightUnits {
    public static final int MAX_WRITE_ATTEMPTS = 3;

    private static final long MIN_BACKOFF_MILLIS = 5;
    private static final long MAX_BACKOFF_MILLIS = 50;

    private final TransactionTemplate transactions;
    private final ConstraintViolations violations;
    private final ReadLanes lanes;
    private final ReportExecutor reportExecutor;
    private final WriteAttemptHook attemptHook;
    private final WritePause writePause;
    private final AtomicInteger inFlight = new AtomicInteger();

    public TransactionalUnitOfWork(PlatformTransactionManager transactionManager, ConstraintViolations violations,
                                   ReadLanes lanes, ReportExecutor reportExecutor, WriteAttemptHook attemptHook,
                                   WritePause writePause) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.violations = violations;
        this.lanes = lanes;
        this.reportExecutor = reportExecutor;
        this.attemptHook = attemptHook;
        this.writePause = writePause;
    }

    @Override
    public int inFlight() {
        return inFlight.get();
    }

    @Override
    public <T> T write(Supplier<T> unit) {
        Optional<UnitFrame> current = UnitFrame.current();
        if (current.isPresent()) {
            requireJoinableByWrite(current.get());
            return unit.get();
        }
        writePause.enter();
        inFlight.incrementAndGet();
        try {
            return writeWithRetries(unit);
        } finally {
            inFlight.decrementAndGet();
            writePause.exit();
        }
    }

    @Override
    public void write(Runnable unit) {
        write(() -> {
            unit.run();
            return null;
        });
    }

    @Override
    public <T> T read(Supplier<T> unit) {
        if (UnitFrame.current().isPresent()) {
            return unit.get();
        }
        inFlight.incrementAndGet();
        try {
            return inTransaction(UnitScope.READ, 0, unit);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    @Override
    public <T> T report(Supplier<T> unit) {
        return onLane(lanes.reports(), unit);
    }

    @Override
    public <T> T search(Supplier<T> unit) {
        return onLane(lanes.searches(), unit);
    }

    private <T> T onLane(ReportLane lane, Supplier<T> unit) {
        UnitFrame.current().ifPresent(frame -> {
            throw new IllegalStateException("A report cannot run inside a " + frame.scope() + " unit");
        });
        return lane.run(() -> reportExecutor.run(() -> runReport(lane, unit)));
    }

    @Override
    public void afterCommit(Runnable effect) {
        UnitFrame frame = UnitFrame.current()
            .filter(candidate -> candidate.scope() == UnitScope.WRITE)
            .orElseThrow(() -> new IllegalStateException("afterCommit needs a running write unit"));
        frame.registerAfterCommit(effect);
    }

    private <T> T runReport(ReportLane lane, Supplier<T> unit) {
        inFlight.incrementAndGet();
        try {
            long deadline = System.nanoTime() + lane.deadline().toNanos();
            return inTransaction(UnitScope.REPORT, deadline, unit);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private static void requireJoinableByWrite(UnitFrame frame) {
        if (frame.scope() != UnitScope.WRITE) {
            throw new IllegalStateException("A write unit cannot run inside a " + frame.scope() + " unit");
        }
    }

    private <T> T writeWithRetries(Supplier<T> unit) {
        for (int attempt = 1; ; attempt++) {
            try {
                return writeAttempt(unit, attempt);
            } catch (RuntimeException failure) {
                if (!violations.isRetryable(failure)) {
                    throw violations.translate(failure);
                }
                if (attempt >= MAX_WRITE_ATTEMPTS) {
                    throw exhausted(failure);
                }
                backOff();
            }
        }
    }

    private <T> T writeAttempt(Supplier<T> unit, int attempt) {
        UnitFrame frame = UnitFrame.enter(UnitScope.WRITE, 0);
        T result;
        try {
            result = transactions.execute(status -> {
                T value = unit.get();
                attemptHook.beforeCommit(attempt);
                return value;
            });
        } finally {
            UnitFrame.exit();
        }
        runAfterCommit(frame);
        return result;
    }

    private <T> T inTransaction(UnitScope scope, long deadlineNanos, Supplier<T> unit) {
        UnitFrame.enter(scope, deadlineNanos);
        try {
            return transactions.execute(status -> unit.get());
        } catch (RuntimeException failure) {
            throw violations.translate(failure);
        } finally {
            UnitFrame.exit();
        }
    }

    private RuntimeException exhausted(RuntimeException failure) {
        if (failure instanceof ConcurrentUpdateException) {
            return failure;
        }
        return new DatabaseBusyException("The database stayed busy after " + MAX_WRITE_ATTEMPTS + " attempts",
            DatabaseBusyException.POOL_RETRY_AFTER, failure);
    }

    private static void runAfterCommit(UnitFrame frame) {
        for (Runnable effect : frame.afterCommitEffects()) {
            try {
                effect.run();
            } catch (RuntimeException e) {
                log.error("An after-commit effect failed; the write itself is committed", e);
            }
        }
    }

    private static void backOff() {
        long millis = ThreadLocalRandom.current().nextLong(MIN_BACKOFF_MILLIS, MAX_BACKOFF_MILLIS + 1);
        try {
            Thread.sleep(Duration.ofMillis(millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DatabaseBusyException("Interrupted while retrying a write", DatabaseBusyException.POOL_RETRY_AFTER, e);
        }
    }
}
