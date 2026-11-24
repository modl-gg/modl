package gg.modl.backend.hostedimport.job;

import gg.modl.backend.hostedimport.archive.ArchiveDocument;
import gg.modl.backend.hostedimport.archive.ArchiveManifest;
import gg.modl.backend.hostedimport.archive.NdjsonLines;
import gg.modl.backend.hostedimport.archive.UnreadableDocumentException;
import gg.modl.backend.hostedimport.archive.VerifiedEntryStream;
import gg.modl.backend.hostedimport.mapping.ArchiveCollection;
import gg.modl.backend.hostedimport.model.ImportFailure;
import gg.modl.backend.hostedimport.model.ImportReason;
import gg.modl.backend.hostedimport.model.ImportSubject;
import gg.modl.backend.hostedimport.model.ImportTally;
import gg.modl.backend.migration.service.ImportUnits;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import lombok.Builder;

@Builder
record CollectionImport<T>(
    ArchiveCollection collection,
    ImportSubject subject,
    long positionOffset,
    boolean remapsCommittedLines,
    LineMapper<T> mapper,
    ToIntFunction<T> rowsOf,
    Consumer<List<T>> write
) {
    static final long MAX_BATCH_BYTES = 2L * 1024 * 1024;
    private static final long STATE_EVERY_LINES = 5_000;

    static <T> CollectionImportBuilder<T> of(ArchiveCollection collection, ImportSubject subject) {
        return CollectionImport.<T>builder()
            .collection(collection)
            .subject(subject)
            .rowsOf(item -> 1);
    }

    void run(PhaseRun run) {
        Optional<ArchiveManifest.CollectionRecord> record = run.index().manifest().collections().stream()
            .filter(candidate -> candidate.name().equals(collection.archiveName()))
            .findFirst();
        if (record.isEmpty()) {
            return;
        }
        ArchiveManifest.CollectionRecord entry = record.get();
        try (VerifiedEntryStream stream = run.archive().open(entry.path(), entry.size(), entry.sha256())) {
            importLines(run, new NdjsonLines(stream));
        } catch (IOException | UncheckedIOException e) {
            throw new ImportFailure(ImportReason.ARCHIVE_MISSING, e);
        }
    }

    private void importLines(PhaseRun run, NdjsonLines lines) {
        long committed = Math.max(0, run.cursor() - positionOffset);
        long saved = committed;
        List<Positioned<T>> batch = new ArrayList<>();
        int rows = 0;
        long bytes = 0;
        while (lines.next()) {
            run.requireRunning();
            long line = lines.lineNumber();
            if (line <= committed) {
                if (remapsCommittedLines) {
                    map(lines, line);
                    run.issues().clear();
                }
                continue;
            }
            Optional<T> mapped = map(lines, line);
            if (mapped.isPresent()) {
                ImportTally reported = run.issues().drain();
                int itemRows = rowsOf.applyAsInt(mapped.get());
                if (!batch.isEmpty() && (rows + itemRows > ImportUnits.ROWS_PER_UNIT || bytes + lines.lineBytes() > MAX_BATCH_BYTES)) {
                    saved = flush(run, batch);
                    batch = new ArrayList<>();
                    rows = 0;
                    bytes = 0;
                }
                batch.add(new Positioned<>(mapped.get(), line + positionOffset, collection.archiveName() + ":" + line, reported));
                rows += itemRows;
                bytes += lines.lineBytes();
            } else if (batch.isEmpty() && line - saved >= STATE_EVERY_LINES) {
                run.commitState(line + positionOffset, () -> { });
                saved = line;
            }
        }
        long last = lines.lineNumber();
        if (!batch.isEmpty()) {
            batch.set(batch.size() - 1, batch.getLast().through(last + positionOffset, run.issues().drain()));
            saved = flush(run, batch);
        }
        if (last > saved) {
            run.commitState(last + positionOffset, () -> { });
        }
    }

    private Optional<T> map(NdjsonLines lines, long line) {
        try {
            return mapper.map(ArchiveDocument.of(lines.decode()), line);
        } catch (UnreadableDocumentException unreadableAndAlreadyReportedByAnalysis) {
            return Optional.empty();
        }
    }

    private long flush(PhaseRun run, List<Positioned<T>> batch) {
        run.commit(batch, subject, write);
        return batch.getLast().position() - positionOffset;
    }

    @FunctionalInterface
    interface LineMapper<T> {
        Optional<T> map(ArchiveDocument document, long line);
    }
}
