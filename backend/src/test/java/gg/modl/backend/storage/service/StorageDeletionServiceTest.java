package gg.modl.backend.storage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.storage.StorageContractFixture;
import gg.modl.backend.storage.service.StorageDeletionService.SettleOutcome;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.io.TempDir;

class StorageDeletionServiceTest {
    private static final String KEY = "modl_instance/evidence/PUN-1/clip.mp4";

    @TempDir
    Path directory;

    @SqlContractTest
    void theRecordsAreCommittedGoneBeforeTheObjectsAreTouched(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory, new RetryInjection())) {
            storage.file(KEY, 40);
            storage.reservation(KEY, 40);
            assertThat(storage.total().bytes()).isEqualTo(40);
            when(storage.objectStorage().deleteAll(anyCollection())).thenAnswer(invocation -> {
                assertThat(CompletableFuture.supplyAsync(() -> storage.hasFile(KEY) || storage.hasReservation(KEY)).join()).isFalse();
                return Set.of();
            });

            StorageDeletion deletion = storage.deletion().delete(storage.server(), List.of(KEY));

            assertThat(deletion.existed()).isTrue();
            assertThat(deletion.undeletedObjects()).isEmpty();
            assertThat(storage.total().bytes()).isZero();
            verify(storage.objectStorage()).deleteAll(List.of(KEY));
            verify(storage.listener()).onStoredObjectsDeleted(storage.server(), List.of(KEY));
        }
    }

    @SqlContractTest
    void anUnknownKeyReportsThatNothingExisted(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            assertThat(storage.deletion().delete(storage.server(), List.of(KEY)).existed()).isFalse();
            assertThat(storage.deletion().delete(storage.server(), List.of())).isEqualTo(StorageDeletion.NOTHING);
        }
    }

    @SqlContractTest
    void anUnrecordedObjectStillCountsAsExisting(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            when(storage.objectStorage().exists(KEY)).thenReturn(true);

            assertThat(storage.deletion().delete(storage.server(), List.of(KEY)).existed()).isTrue();
        }
    }

    @SqlContractTest
    void objectsThatCouldNotBeDeletedAreReported(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.file(KEY, 1);
            when(storage.objectStorage().deleteAll(List.of(KEY))).thenReturn(Set.of(KEY));

            assertThat(storage.deletion().delete(storage.server(), List.of(KEY)).undeletedObjects()).containsExactly(KEY);
            assertThat(storage.hasFile(KEY)).isFalse();
        }
    }

    @SqlContractTest
    void aFailingListenerDoesNotFailTheDeletion(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.file(KEY, 1);
            doThrow(new IllegalStateException("down")).when(storage.listener()).onStoredObjectsDeleted(any(), anyCollection());

            assertThat(storage.deletion().delete(storage.server(), List.of(KEY)).existed()).isTrue();
        }
    }

    @SqlContractTest
    void discardReleasesTheReservationOnlyAfterTheObjectIsGone(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.reservation(KEY, 10);
            when(storage.objectStorage().delete(KEY)).thenReturn(false);

            assertThat(storage.deletion().discard(storage.server(), KEY)).isFalse();
            assertThat(storage.hasReservation(KEY)).isTrue();

            when(storage.objectStorage().delete(KEY)).thenReturn(true);

            assertThat(storage.deletion().discard(storage.server(), KEY)).isTrue();
            assertThat(storage.hasReservation(KEY)).isFalse();
        }
    }

    @SqlContractTest
    void settlingAReservationClaimedByAConfirmationKeepsTheObject(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            assertThat(storage.deletion().settle(storage.server(), KEY)).isEqualTo(SettleOutcome.CONFIRMED);
            verify(storage.objectStorage(), never()).delete(any());
        }
    }

    @SqlContractTest
    void settlingAReservationOfARecordedFileKeepsTheObjectAndDropsTheReservation(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.file(KEY, 1);
            storage.reservation(KEY, 1);

            assertThat(storage.deletion().settle(storage.server(), KEY)).isEqualTo(SettleOutcome.CONFIRMED);
            assertThat(storage.hasFile(KEY)).isTrue();
            assertThat(storage.hasReservation(KEY)).isFalse();
            verify(storage.objectStorage(), never()).delete(any());
        }
    }

    @SqlContractTest
    void settlingAnAbandonedUploadDeletesItsObjectAndNotifiesListeners(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory, new RetryInjection())) {
            storage.reservation(KEY, 10);

            assertThat(storage.deletion().settle(storage.server(), KEY)).isEqualTo(SettleOutcome.ABANDONED);
            assertThat(storage.hasReservation(KEY)).isFalse();
            verify(storage.objectStorage()).delete(KEY);
            verify(storage.listener()).onStoredObjectsDeleted(storage.server(), List.of(KEY));
        }
    }

    @SqlContractTest
    void anAbandonedObjectThatCannotBeDeletedStaysReservedForTheNextRun(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.reservation(KEY, 10);
            when(storage.objectStorage().delete(KEY)).thenReturn(false);

            assertThat(storage.deletion().settle(storage.server(), KEY)).isEqualTo(SettleOutcome.RETAINED);
            assertThat(storage.reservations().sumReserved().total()).isEqualTo(10);
            verify(storage.listener(), never()).onStoredObjectsDeleted(any(), anyCollection());
        }
    }
}
