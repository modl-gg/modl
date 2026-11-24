package gg.modl.backend.hostedimport.job;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.hostedimport.intake.ImportArchiveFiles;
import gg.modl.backend.hostedimport.model.ImportJob;
import gg.modl.backend.hostedimport.model.ImportReason;
import gg.modl.backend.hostedimport.model.ImportStatus;
import gg.modl.backend.hostedimport.repository.HostedImportStateRepository;
import gg.modl.backend.infrastructure.scheduling.ScheduledDatabaseJobs;
import gg.modl.backend.update.commit.InstanceCommittedEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImportRecovery {
    static final Duration UNCONFIRMED_LIFETIME = Duration.ofDays(2);
    private static final Set<ImportStatus> UNCONFIRMED = EnumSet.of(ImportStatus.RECEIVING, ImportStatus.REVIEW, ImportStatus.FAILED);

    private final ImportStates states;
    private final HostedImportStateRepository stateRepository;
    private final ImportArchiveFiles files;
    private final ImportWorker worker;
    private final ImportVerifier verifier;
    private final ImportApplier applier;
    private final ArchiveSession session;
    private final ScheduledDatabaseJobs databaseJobs;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    @EventListener(InstanceCommittedEvent.class)
    public void recover() {
        Optional<ImportJob> current = states.current();
        deleteForeignArchives(current);
        current.ifPresent(this::recover);
    }

    private void recover(ImportJob job) {
        Instant now = clock.instant();
        switch (job.status()) {
            case RECEIVING -> {
                if (!files.exists(job.id())) {
                    states.transition(job.id(), ImportStatus.RECEIVING, current -> current.failed(ImportReason.ARCHIVE_MISSING, now));
                } else if (files.size(job.id()) > job.receivedBytes()) {
                    files.truncate(job.id(), job.receivedBytes());
                }
            }
            case DOWNLOADING -> {
                files.delete(job.id());
                states.transition(job.id(), ImportStatus.DOWNLOADING, current -> current.failed(ImportReason.LINK_REENTRY_REQUIRED, now));
            }
            case VERIFYING, REVIEW -> {
                states.transition(job.id(), job.status(), current -> current.withStatus(ImportStatus.VERIFYING).withUpdatedAt(now));
                worker.submit(() -> verifier.verify(job.id()));
            }
            case APPLYING -> worker.submit(() -> applier.apply(job.id(), true));
            case PAUSED -> {
                if (!files.exists(job.id())) {
                    states.transition(job.id(), ImportStatus.PAUSED, current -> current.paused(ImportReason.ARCHIVE_MISSING, now));
                }
            }
            case NONE, COMPLETED, ABANDONED, FAILED -> files.delete(job.id());
        }
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT15M")
    public void expireUnconfirmed() {
        databaseJobs.run("Removing unconfirmed import archives", () -> {
            Instant cutoff = clock.instant().minus(UNCONFIRMED_LIFETIME);
            states.current()
                .filter(job -> UNCONFIRMED.contains(job.status()) && job.updatedAt().isBefore(cutoff))
                .ifPresent(this::expire);
        });
    }

    private void expire(ImportJob job) {
        worker.cancel(job.id());
        unitOfWork.write(() -> {
            stateRepository.find()
                .filter(stored -> stored.job().id().equals(job.id()) && stored.job().status() == job.status())
                .ifPresent(stored -> {
                    stateRepository.deleteAll();
                    unitOfWork.afterCommit(() -> {
                        files.delete(job.id());
                        session.forget();
                    });
                });
        });
        log.info("Removed an unconfirmed import from modl.gg after {} hours", UNCONFIRMED_LIFETIME.toHours());
    }

    private void deleteForeignArchives(Optional<ImportJob> current) {
        files.archives().stream()
            .filter(archive -> current.map(ImportJob::id).filter(archive.importId()::equals).isEmpty())
            .forEach(archive -> files.delete(archive.importId()));
    }
}
