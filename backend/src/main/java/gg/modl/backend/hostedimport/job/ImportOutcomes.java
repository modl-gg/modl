package gg.modl.backend.hostedimport.job;

import gg.modl.backend.database.ConstraintViolations;
import gg.modl.backend.hostedimport.intake.ImportArchiveFiles;
import gg.modl.backend.hostedimport.model.ImportFailure;
import gg.modl.backend.hostedimport.model.ImportReason;
import gg.modl.backend.hostedimport.model.ImportStatus;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImportOutcomes {
    private static final long RETRY_SECONDS = 15;

    private final ImportStates states;
    private final ConstraintViolations violations;
    private final ImportArchiveFiles files;
    private final ArchiveSession session;
    private final ImportNotices notices;
    private final Clock clock;

    public void failed(String importId, ImportStatus during, Throwable failure, BooleanSupplier stopRequested) {
        if (stopRequested.getAsBoolean()) {
            return;
        }
        ImportReason reason = failure instanceof ImportFailure known ? known.reason() : classify(failure);
        if (reason == ImportReason.UNEXPECTED) {
            logWithoutContent(failure);
        }
        recordUntilStored(stopRequested, () -> {
            if (states.transition(importId, during, job -> job.failed(reason, clock.instant())).isPresent()) {
                session.forget();
                files.delete(importId);
            }
        });
    }

    public void paused(String importId, Throwable failure, BooleanSupplier stopRequested) {
        if (stopRequested.getAsBoolean()) {
            return;
        }
        ImportReason reason = pauseReason(failure);
        if (reason == ImportReason.UNEXPECTED) {
            logWithoutContent(failure);
        }
        if (reason == ImportReason.ARCHIVE_MISSING) {
            session.forget();
        }
        recordUntilStored(stopRequested, () -> {
            if (states.transition(importId, ImportStatus.APPLYING, job -> job.paused(reason, clock.instant())).isPresent()) {
                notices.paused(reason);
            }
        });
    }

    ImportReason pauseReason(Throwable failure) {
        if (failure instanceof ImportFailure known) {
            if (known.reason().pausesTheImport()) {
                return known.reason();
            }
            return known.reason().name().startsWith("ARCHIVE_") ? ImportReason.ARCHIVE_MISSING : ImportReason.UNEXPECTED;
        }
        return classify(failure);
    }

    private ImportReason classify(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            if (violations.isDiskFull(runtime)) {
                return ImportReason.DISK_SPACE;
            }
            if (violations.isUnavailable(runtime)) {
                return ImportReason.DATABASE_UNAVAILABLE;
            }
        }
        return ImportReason.UNEXPECTED;
    }

    private static void recordUntilStored(BooleanSupplier stopRequested, Runnable record) {
        while (!stopRequested.getAsBoolean()) {
            try {
                record.run();
                return;
            } catch (RuntimeException failure) {
                logWithoutContent(failure);
                try {
                    TimeUnit.SECONDS.sleep(RETRY_SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static void logWithoutContent(Throwable failure) {
        RuntimeException trace = new RuntimeException(causes(failure));
        trace.setStackTrace(failure.getStackTrace());
        log.error("The import from modl.gg met an unexpected error", trace);
    }

    private static String causes(Throwable failure) {
        StringBuilder names = new StringBuilder(failure.getClass().getName());
        Throwable cause = failure.getCause();
        for (int depth = 0; cause != null && depth < 8; depth++) {
            names.append(" caused by ").append(cause.getClass().getName());
            cause = cause.getCause();
        }
        return names.toString();
    }
}
