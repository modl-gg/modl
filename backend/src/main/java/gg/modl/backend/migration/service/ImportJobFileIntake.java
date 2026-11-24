package gg.modl.backend.migration.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.migration.data.ImportEnd;
import gg.modl.backend.migration.data.ImportEnding;
import gg.modl.backend.migration.data.ImportState;
import gg.modl.backend.migration.data.MigrationStatus;
import gg.modl.backend.migration.repository.ImportJobRepository;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ImportJobFileIntake implements ImportFileIntake {
    private final ImportJobRepository importJobRepository;
    private final ImportUploadFiles files;
    private final ImportWorkerLauncher workerLauncher;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    @Override
    public boolean awaitsFile(String jobId) {
        return isIn(jobId, ImportState.UPLOADING);
    }

    @Override
    public boolean claim(String jobId) {
        return unitOfWork.write(() ->
            importJobRepository.transition(jobId, ImportState.UPLOADING, ImportState.RECEIVING, clock.instant()));
    }

    @Override
    public boolean keepReceiving(String jobId) {
        unitOfWork.write(() -> {
            importJobRepository.refreshLease(jobId, ImportState.RECEIVING, clock.instant());
        });
        return isReceiving(jobId);
    }

    @Override
    public boolean isReceiving(String jobId) {
        return isIn(jobId, ImportState.RECEIVING);
    }

    @Override
    public boolean accept(String jobId) {
        Path file = files.accepted(jobId);
        return unitOfWork.write(() -> {
            if (!importJobRepository.beginProcessing(jobId, ImportState.RECEIVING, clock.instant())) {
                return false;
            }
            unitOfWork.afterCommit(() -> workerLauncher.launch(jobId, file));
            return true;
        });
    }

    @Override
    public boolean reopen(String jobId) {
        return unitOfWork.write(() ->
            importJobRepository.transition(jobId, ImportState.RECEIVING, ImportState.UPLOADING, clock.instant()));
    }

    @Override
    public boolean refuse(String jobId, ImportEnding ending) {
        return unitOfWork.write(() ->
            importJobRepository.end(jobId, Set.of(ImportState.UPLOADING), new ImportEnd(ending, null, clock.instant())));
    }

    private boolean isIn(String jobId, ImportState state) {
        Optional<ImportState> current = importJobRepository.findById(jobId).map(MigrationStatus::getStatus);
        return current.filter(state::equals).isPresent();
    }
}
