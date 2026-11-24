package gg.modl.backend.hostedimport.job;

import gg.modl.backend.database.ConcurrentUpdateException;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.hostedimport.model.ImportFailure;
import gg.modl.backend.hostedimport.model.ImportJob;
import gg.modl.backend.hostedimport.model.ImportReason;
import gg.modl.backend.hostedimport.model.ImportStatus;
import gg.modl.backend.hostedimport.repository.HostedImportStateRepository;
import gg.modl.backend.hostedimport.repository.HostedImportStateRepository.StoredJob;
import java.util.Optional;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ImportStates {
    private final HostedImportStateRepository repository;
    private final UnitOfWork unitOfWork;

    public Optional<ImportJob> current() {
        return unitOfWork.read(() -> repository.find().map(StoredJob::job));
    }

    public Optional<ImportJob> current(String importId, ImportStatus status) {
        return current().filter(job -> job.id().equals(importId) && job.status() == status);
    }

    public Optional<ImportJob> transition(String importId, ImportStatus expected, UnaryOperator<ImportJob> change) {
        return unitOfWork.write(() -> transitionInUnit(importId, expected, change));
    }

    public Optional<ImportJob> transitionInUnit(String importId, ImportStatus expected, UnaryOperator<ImportJob> change) {
        return repository.find()
            .filter(stored -> stored.job().id().equals(importId) && stored.job().status() == expected)
            .map(stored -> replaced(stored, change));
    }

    ImportJob applyingInUnit(String importId, UnaryOperator<ImportJob> change) {
        return transitionInUnit(importId, ImportStatus.APPLYING, change)
            .orElseThrow(() -> new ImportFailure(ImportReason.CANCELLED));
    }

    private ImportJob replaced(StoredJob stored, UnaryOperator<ImportJob> change) {
        ImportJob next = change.apply(stored.job());
        if (!repository.replace(stored.version(), next)) {
            throw new ConcurrentUpdateException("The import state changed while it was being updated");
        }
        return next;
    }
}
