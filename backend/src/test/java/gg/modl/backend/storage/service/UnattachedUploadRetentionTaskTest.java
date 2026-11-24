package gg.modl.backend.storage.service;

import static gg.modl.backend.database.jooq.Tables.STORAGE_FILES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.retention.RetentionBatches;
import gg.modl.backend.storage.StorageContractFixture;
import gg.modl.backend.storage.local.LocalObjectStorage;
import gg.modl.backend.storage.local.UploadSigner;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.io.TempDir;

class UnattachedUploadRetentionTaskTest {
    private static final Instant START = StorageContractFixture.START;

    @TempDir
    Path directory;

    @SqlContractTest
    void anUploadNeverSubmittedIsReclaimedWithItsObjectAndAnAttachedOneIsKept(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory, new RetryInjection())) {
            LocalObjectStorage objects = new LocalObjectStorage(
                directory.resolve("objects"), new UploadSigner(() -> "signing-key"), storage.database().clock());
            String abandoned = confirmed(storage, objects, "modl_instance/tickets/new/abandoned.png");
            String attached = confirmed(storage, objects, "modl_instance/evidence/PUN-1/attached.png");
            storage.files().markAttached(List.of(attached));
            when(storage.objectStorage().deleteAll(anyCollection()))
                .thenAnswer(invocation -> objects.deleteAll(invocation.<Collection<String>>getArgument(0)));
            UnattachedUploadRetentionTask task = new UnattachedUploadRetentionTask(storage.deletion());
            Instant expiry = START.plus(StorageContractFixture.UNATTACHED_TTL);

            assertThat(task.purge(storage.server(), expiry)).isZero();
            assertThat(objects.exists(abandoned)).isTrue();

            assertThat(task.purge(storage.server(), expiry.plusMillis(1))).isEqualTo(1);

            assertThat(storage.hasFile(abandoned)).isFalse();
            assertThat(objects.exists(abandoned)).isFalse();
            assertThat(task.purge(storage.server(), expiry.plusSeconds(31_536_000))).isZero();
            assertThat(storage.hasFile(attached)).isTrue();
            assertThat(objects.exists(attached)).isTrue();
            verify(storage.listener()).onStoredObjectsDeleted(storage.server(), List.of(abandoned));
        }
    }

    @SqlContractTest
    void theSweepWorksInBatchesAndFreesTheQuota(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            int expired = RetentionBatches.DEFAULT_BATCH_SIZE + 1;
            storage.database().unitOfWork().write(() -> IntStream.range(0, expired)
                .forEach(index -> storage.file("modl_instance/tickets/new/" + index, 2, START, START.minusSeconds(1))));
            storage.file("modl_instance/article/kept.png", 5);
            assertThat(storage.total().bytes()).isEqualTo(expired * 2L + 5);

            assertThat(new UnattachedUploadRetentionTask(storage.deletion()).purge(storage.server(), START)).isEqualTo(expired);

            verify(storage.objectStorage(), times(2)).deleteAll(anyCollection());
            assertThat(storage.database().dsl().fetchCount(STORAGE_FILES)).isEqualTo(1);
            assertThat(storage.total().bytes()).isEqualTo(5);
        }
    }

    @SqlContractTest
    void nothingExpiredTouchesNoObject(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.file("modl_instance/evidence/PUN-1/a.png", 1, START.minusSeconds(999_999), null);

            assertThat(new UnattachedUploadRetentionTask(storage.deletion()).purge(storage.server(), START)).isZero();
            verify(storage.objectStorage(), never()).deleteAll(anyCollection());
        }
    }

    private static String confirmed(StorageContractFixture storage, LocalObjectStorage objects, String key) {
        objects.write(key, new ByteArrayInputStream(new byte[] {1}), 1, "image/png");
        storage.reservation(key, 1);
        assertThat(storage.metadata().confirmUpload(storage.server(), key, 1, "image/png"))
            .isEqualTo(UploadConfirmation.RECORDED);
        return key;
    }
}
