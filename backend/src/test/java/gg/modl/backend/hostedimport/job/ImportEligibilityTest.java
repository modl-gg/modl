package gg.modl.backend.hostedimport.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.databasemove.DatabaseMoves;
import gg.modl.backend.hostedimport.repository.ImportTargetRepository;
import gg.modl.backend.migration.service.ImportKind;
import gg.modl.backend.migration.service.TestImports;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.StorageMoves;
import gg.modl.backend.support.TestServers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ImportEligibilityTest {
    private final StorageMoves storageMoves = mock(StorageMoves.class);
    private final DatabaseMoves databaseMoves = mock(DatabaseMoves.class);
    private final AtomicBoolean pluginImporting = new AtomicBoolean();
    private final AtomicBoolean hostedImporting = new AtomicBoolean();
    private final ImportEligibility eligibility = new ImportEligibility(mock(ImportTargetRepository.class),
        TestImports.running(() -> List.of(TestImports.of(ImportKind.PLUGIN, pluginImporting::get),
            TestImports.of(ImportKind.HOSTED, hostedImporting::get))), storageMoves, databaseMoves, Clock.fixed(Instant.parse("2026-11-22T00:00:00Z"), ZoneOffset.UTC));
    private final Server server = TestServers.server("Server", "owner@example.com");

    @Test
    void anImportCannotStartWhileTheDatabaseMoves() {
        when(databaseMoves.isMovingDatabase()).thenReturn(true);

        assertThat(eligibility.dataBlockers(server)).contains(ImportEligibility.DATABASE_MOVING);
    }

    @Test
    void anImportCannotStartWhileUploadedFilesAreMovingToAnotherStorage() {
        when(storageMoves.isMovingFiles()).thenReturn(true);

        assertThat(eligibility.dataBlockers(server)).contains(ImportEligibility.STORAGE_MOVING);
    }

    @Test
    void aRunningPluginImportBlocksButTheHostedImportItselfDoesNot() {
        hostedImporting.set(true);
        assertThat(eligibility.dataBlockers(server)).doesNotContain(ImportEligibility.PLUGIN_IMPORT_ACTIVE);

        pluginImporting.set(true);
        assertThat(eligibility.dataBlockers(server)).contains(ImportEligibility.PLUGIN_IMPORT_ACTIVE);
    }

    @Test
    void settledStorageDoesNotBlockAnImport() {
        when(storageMoves.isMovingFiles()).thenReturn(false);

        assertThat(eligibility.dataBlockers(server)).doesNotContain(ImportEligibility.STORAGE_MOVING);
    }
}
