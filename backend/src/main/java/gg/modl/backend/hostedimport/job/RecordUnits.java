package gg.modl.backend.hostedimport.job;

import gg.modl.backend.database.ConstraintViolations;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.hostedimport.model.ImportSubject;
import gg.modl.backend.hostedimport.model.ImportTally;
import gg.modl.backend.hostedimport.model.IssueLog;
import gg.modl.backend.infrastructure.exception.ValidationException;
import java.time.Clock;
import java.util.List;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RecordUnits {
    private static final long MIN_PAUSE_MILLIS = 10;
    private static final long MAX_PAUSE_MILLIS = 250;
    private static final int BUSY_ATTEMPTS = 5;
    private static final long BUSY_BACKOFF_MILLIS = 500;

    private final UnitOfWork unitOfWork;
    private final ConstraintViolations violations;
    private final ImportStates states;
    private final Clock clock;

    <T> void commit(String importId, List<Positioned<T>> batch, ImportSubject subject, Consumer<List<T>> write, IssueLog issues) {
        if (batch.isEmpty()) {
            return;
        }
        commitRange(new Batch<>(importId, subject, write, issues), batch);
    }

    void commitState(String importId, long position, IssueLog issues, Runnable write) {
        ImportTally reported = issues.drain();
        inUnit(() -> {
            issues.clear();
            write.run();
            advance(importId, position, reported.plus(issues.tally()));
        });
        issues.clear();
    }

    private <T> void commitRange(Batch<T> batch, List<Positioned<T>> range) {
        long position = range.getLast().position();
        ImportTally reported = range.stream().map(Positioned::reported).reduce(ImportTally.EMPTY, ImportTally::plus);
        IssueLog issues = batch.issues();
        try {
            inUnit(() -> {
                issues.clear();
                batch.write().accept(range.stream().map(Positioned::item).toList());
                advance(batch.importId(), position, reported.plus(issues.tally()));
            });
            issues.clear();
        } catch (RuntimeException failure) {
            if (!isRejected(failure)) {
                throw failure;
            }
            issues.clear();
            if (range.size() == 1) {
                ImportTally skipped = reported.plus(ImportTally.rejected(batch.subject(), range.getFirst().sample()));
                inUnit(() -> advance(batch.importId(), position, skipped));
                return;
            }
            int middle = range.size() / 2;
            commitRange(batch, range.subList(0, middle));
            commitRange(batch, range.subList(middle, range.size()));
        }
    }

    private boolean isRejected(RuntimeException failure) {
        return failure instanceof ValidationException || violations.isDataRejection(failure);
    }

    private void inUnit(Runnable unit) {
        for (int attempt = 1; ; attempt++) {
            long started = System.nanoTime();
            try {
                unitOfWork.write(unit);
                pause(Math.clamp((System.nanoTime() - started) / 1_000_000, MIN_PAUSE_MILLIS, MAX_PAUSE_MILLIS));
                return;
            } catch (RuntimeException failure) {
                if (attempt >= BUSY_ATTEMPTS || !violations.isBusy(failure)) {
                    throw failure;
                }
                pause(BUSY_BACKOFF_MILLIS * attempt);
            }
        }
    }

    private void advance(String importId, long position, ImportTally tally) {
        states.applyingInUnit(importId, job -> job.withCursor(position).withPhaseDone(position)
            .withTally(job.tally().plus(tally)).withUpdatedAt(clock.instant()));
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Batch<T>(String importId, ImportSubject subject, Consumer<List<T>> write, IssueLog issues) {
    }
}
