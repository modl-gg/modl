package gg.modl.backend.hostedimport.job;

import gg.modl.backend.hostedimport.mapping.ArchiveIndex;
import gg.modl.backend.hostedimport.model.ImportPhase;
import gg.modl.backend.hostedimport.model.ImportSubject;
import gg.modl.backend.hostedimport.model.IssueCode;
import gg.modl.backend.hostedimport.storage.ImportedFileWriter;
import gg.modl.backend.hostedimport.storage.ImportedKeyPlan.PlannedObject;
import gg.modl.backend.storage.ConfirmedStorageTotal;
import gg.modl.backend.storage.UploadOrigin;
import gg.modl.backend.storage.repository.StorageFile;
import gg.modl.backend.storage.repository.StorageFileRepository;
import gg.modl.backend.storage.service.StorageKeyUtils;
import gg.modl.backend.database.UnitOfWork;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FilesStep implements PhaseStep {
    private static final int MAX_OBJECTS_PER_UNIT = 25;
    private static final long MAX_BYTES_PER_UNIT = 64L * 1024 * 1024;
    private static final int FILE_NAME_LIMIT = 255;

    private final ImportedFileWriter writer;
    private final StorageFileRepository storageFiles;
    private final ConfirmedStorageTotal confirmedStorageTotal;
    private final UnitOfWork unitOfWork;

    @Override
    public ImportPhase phase() {
        return ImportPhase.FILES;
    }

    @Override
    public long total(ArchiveIndex index) {
        return index.keyPlan().size();
    }

    @Override
    public void run(PhaseRun run) {
        List<PlannedObject> objects = run.index().keyPlan().objects();
        List<Positioned<StorageFile>> batch = new ArrayList<>();
        long batchBytes = 0;
        for (int index = (int) run.cursor(); index < objects.size(); index++) {
            run.requireRunning();
            run.requireDisk();
            PlannedObject planned = objects.get(index);
            ImportedFileWriter.Outcome outcome = writer.write(run.archive(), planned, batchBytes);
            long position = index + 1L;
            if (outcome == ImportedFileWriter.Outcome.CONFLICT) {
                flush(run, batch);
                run.issues().report(IssueCode.FILE_CONFLICT, planned.source().key());
                run.issues().skipped(ImportSubject.FILES);
                batch = new ArrayList<>();
                batchBytes = 0;
                run.commitState(position, () -> { });
                continue;
            }
            StorageFile file = record(run, planned);
            run.issues().imported(ImportSubject.FILES);
            batch.add(new Positioned<>(file, position, planned.source().key(), run.issues().drain()));
            batchBytes += planned.source().size();
            if (batch.size() >= MAX_OBJECTS_PER_UNIT || batchBytes >= MAX_BYTES_PER_UNIT) {
                flush(run, batch);
                batch = new ArrayList<>();
                batchBytes = 0;
            }
        }
        flush(run, batch);
    }

    private void flush(PhaseRun run, List<Positioned<StorageFile>> batch) {
        run.commit(batch, ImportSubject.FILES, files -> files.forEach(file -> {
            if (storageFiles.insertIfAbsent(file, UploadOrigin.AUTHENTICATED, null)) {
                unitOfWork.afterCommit(() -> confirmedStorageTotal.added(file.size()));
            }
        }));
    }

    private static StorageFile record(PhaseRun run, PlannedObject planned) {
        ArchiveIndex.StorageFileRecord archived = run.index().storageFiles().get(planned.source().key());
        String fileName = archived != null && archived.fileName() != null ? archived.fileName() : planned.fileName();
        return new StorageFile(
            planned.localKey(),
            run.context().fit().cut(fileName, FILE_NAME_LIMIT),
            planned.source().size(),
            planned.contentType(),
            StorageKeyUtils.categorizeFile(planned.localKey()),
            Objects.requireNonNullElse(archived == null ? null : archived.createdAt(), run.context().exportTime()));
    }
}
