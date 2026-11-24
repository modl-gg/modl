package gg.modl.backend.migration.repository;

import gg.modl.backend.migration.data.ExportCounts;
import gg.modl.backend.migration.data.ImportEnd;
import gg.modl.backend.migration.data.ImportState;
import gg.modl.backend.migration.data.ImportedCounts;
import gg.modl.backend.migration.data.MigrationStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public interface ImportJobRepository {
    void insert(MigrationStatus job);

    Optional<MigrationStatus> findById(String id);

    Optional<MigrationStatus> findLatest();

    Optional<MigrationStatus> findActive();

    boolean transition(String id, ImportState from, ImportState to, Instant at);

    boolean refreshLease(String id, ImportState state, Instant at);

    boolean recordExport(String id, ImportState to, ExportCounts counts, Instant at);

    boolean beginProcessing(String id, ImportState from, Instant at);

    boolean recordProcessed(String id, ImportedCounts counts, Instant at);

    boolean complete(String id, ImportedCounts counts, Instant at);

    boolean end(String id, Collection<ImportState> from, ImportEnd end);

    long failStale(ImportState state, Instant staleBefore, ImportEnd end);
}
