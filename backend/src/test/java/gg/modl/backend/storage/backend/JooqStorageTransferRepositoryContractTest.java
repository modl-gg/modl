package gg.modl.backend.storage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.storage.StorageType;
import gg.modl.backend.storage.backend.StorageTransfer.TransferCounts;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.io.TempDir;

class JooqStorageTransferRepositoryContractTest {
    private static final Instant NOW = SqlTestDatabase.START;

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void theLatestTransferRoundTripsWithItsProgress(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqStorageTransferRepository repository = repository(database);
            repository.insert(StorageTransfer.started("t1", StorageType.LOCAL, StorageType.S3, NOW));
            StorageTransfer later = StorageTransfer.started("t2", StorageType.S3, StorageType.LOCAL, NOW.plusSeconds(5));
            repository.insert(later);

            StorageTransfer progressed = later.progressed(new TransferCounts(3, 300, 2, 1), "x.png failed", NOW.plusSeconds(9));
            assertThat(repository.saveWhileRunning(progressed)).isTrue();

            assertThat(repository.latest()).contains(progressed);
        }
    }

    @SqlContractTest
    void aFinishedTransferIgnoresLateProgressFromItsJob(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqStorageTransferRepository repository = repository(database);
            StorageTransfer running = StorageTransfer.started("t1", StorageType.LOCAL, StorageType.S3, NOW);
            repository.insert(running);
            StorageTransfer cancelled = running.finished(StorageTransferState.FAILED, NOW.plusSeconds(1));
            repository.save(cancelled);

            assertThat(repository.saveWhileRunning(running.progressed(new TransferCounts(1, 1, 0, 0), null, NOW.plusSeconds(2))))
                .isFalse();
            assertThat(repository.latest()).contains(cancelled);
        }
    }

    @SqlContractTest
    void onlyTheNewestFinishedTransfersAreKept(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqStorageTransferRepository repository = repository(database);
            for (int index = 0; index < 5; index++) {
                StorageTransfer transfer = StorageTransfer.started("t" + index, StorageType.LOCAL, StorageType.S3, NOW.plusSeconds(index));
                repository.insert(transfer);
                repository.save(transfer.finished(StorageTransferState.DONE, NOW.plusSeconds(index)));
            }

            assertThat(repository.deleteFinishedBeyondNewest(2)).isEqualTo(3);
            assertThat(repository.latest()).hasValueSatisfying(latest -> assertThat(latest.id()).isEqualTo("t4"));
        }
    }

    private static JooqStorageTransferRepository repository(SqlTestDatabase database) {
        return new JooqStorageTransferRepository(database.dsl(), database.unitOfWork());
    }
}
