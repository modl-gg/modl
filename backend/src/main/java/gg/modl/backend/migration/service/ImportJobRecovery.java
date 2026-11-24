package gg.modl.backend.migration.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.migration.data.ImportEnd;
import gg.modl.backend.migration.data.ImportEnding;
import gg.modl.backend.migration.data.ImportState;
import gg.modl.backend.migration.data.MigrationStatus;
import gg.modl.backend.migration.repository.ImportJobRepository;
import gg.modl.backend.update.commit.InstanceCommittedEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImportJobRecovery {
    static final String INTERRUPTED_MESSAGE = "The modl server restarted and the import could not continue";

    private final ImportJobRepository importJobRepository;
    private final ImportUploadFiles files;
    private final ImportWorkerLauncher workerLauncher;
    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private final AtomicReference<@Nullable InterruptedImport> waitingForServer = new AtomicReference<>();

    @EventListener(InstanceCommittedEvent.class)
    public void recover() {
        prepareResume();
        resumeProcessing();
    }

    void prepareResume() {
        Optional<InterruptedImport> resumable = importJobRepository.findActive().flatMap(this::recover);
        resumable.ifPresent(waitingForServer::set);
        deleteLeftovers(resumable);
    }

    void resumeProcessing() {
        InterruptedImport resumable = waitingForServer.getAndSet(null);
        if (resumable != null) {
            workerLauncher.launch(resumable.jobId(), resumable.file());
            log.info("Import job {} was interrupted by a restart and is being processed again", resumable.jobId());
        }
    }

    private Optional<InterruptedImport> recover(MigrationStatus job) {
        String jobId = job.getId();
        return switch (job.getStatus()) {
            case BUILDING, UPLOADING -> {
                unitOfWork.write(() -> {
                    importJobRepository.refreshLease(jobId, job.getStatus(), clock.instant());
                });
                yield Optional.empty();
            }
            case RECEIVING -> {
                unitOfWork.write(() -> {
                    importJobRepository.transition(jobId, ImportState.RECEIVING, ImportState.UPLOADING, clock.instant());
                });
                yield Optional.empty();
            }
            case PROCESSING -> resumeOnce(jobId);
            case COMPLETED, FAILED -> Optional.empty();
        };
    }

    private Optional<InterruptedImport> resumeOnce(String jobId) {
        Optional<Path> file = claimAcceptedFile(jobId);
        unitOfWork.write(() -> {
            if (file.isPresent()) {
                importJobRepository.beginProcessing(jobId, ImportState.PROCESSING, clock.instant());
            } else {
                importJobRepository.end(jobId, Set.of(ImportState.PROCESSING),
                    new ImportEnd(ImportEnding.INTERRUPTED, INTERRUPTED_MESSAGE, clock.instant()));
            }
        });
        return file.map(claimed -> new InterruptedImport(jobId, claimed));
    }

    private Optional<Path> claimAcceptedFile(String jobId) {
        if (!Files.isRegularFile(files.accepted(jobId))) {
            return Optional.empty();
        }
        try {
            return Optional.of(files.markResumed(jobId));
        } catch (IOException e) {
            log.warn("The file of import job {} could not be claimed for another run", jobId, e);
            return Optional.empty();
        }
    }

    private void deleteLeftovers(Optional<InterruptedImport> resumable) {
        try {
            for (Path upload : files.uploads()) {
                if (resumable.filter(kept -> kept.file().equals(upload)).isEmpty()) {
                    Files.deleteIfExists(upload);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to remove leftover import uploads", e);
        }
    }

    private record InterruptedImport(String jobId, Path file) {
    }
}
