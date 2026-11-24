package gg.modl.backend.storage.service;

import static gg.modl.backend.database.jooq.Tables.STORAGE_FILES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.database.ValueTooLongException;
import gg.modl.backend.storage.StorageContractFixture;
import gg.modl.backend.storage.repository.StorageFile;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;

class StorageMetadataServiceTest {
    private static final String KEY = "modl_instance/tickets/T-1/file.png";

    @TempDir
    Path directory;

    @SqlContractTest
    void confirmingConsumesTheReservationAndRecordsTheCategorisedFileOnce(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory, new RetryInjection())) {
            storage.reservation(KEY, 42);
            assertThat(storage.total().bytes()).isZero();

            UploadConfirmation confirmation = storage.metadata().confirmUpload(storage.server(), KEY, 42, "image/png");

            assertThat(confirmation).isEqualTo(UploadConfirmation.RECORDED);
            assertThat(storage.hasReservation(KEY)).isFalse();
            assertThat(storage.files().findByKey(KEY)).contains(
                new StorageFile(KEY, "file.png", 42, "image/png", "ticket", StorageContractFixture.START));
            assertThat(storage.total().bytes()).isEqualTo(42);
        }
    }

    @SqlContractTest
    void uploadsAwaitingASubmissionExpireAfterADay(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            for (String key : List.of(KEY, "modl_instance/ticket/a.png", "modl_instance/evidence/P/a.png", "modl_instance/appeal/new/a.png")) {
                confirm(storage, key);

                assertThat(storage.unattachedExpiryOf(key))
                    .isEqualTo(StorageContractFixture.START.plus(StorageContractFixture.UNATTACHED_TTL));
            }
        }
    }

    @SqlContractTest
    void filesWithoutASubmissionStepNeverExpire(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            for (String key : List.of("modl_instance/replays/a.modlreplay", "modl_instance/icons_panel/a.png",
                "modl_instance/article/a.png", "modl_instance/server-icon/a.png")) {
                confirm(storage, key);

                assertThat(storage.unattachedExpiryOf(key)).isNull();
            }
        }
    }

    @SqlContractTest
    void aRepeatedConfirmationKeepsTheAttachmentAndTheRecord(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            confirm(storage, KEY);
            storage.files().markAttached(List.of(KEY));

            UploadConfirmation repeated = storage.metadata().confirmUpload(storage.server(), KEY, 99, "image/gif");

            assertThat(repeated).isEqualTo(UploadConfirmation.ALREADY_RECORDED);
            assertThat(storage.unattachedExpiryOf(KEY)).isNull();
            assertThat(storage.files().findByKey(KEY)).get().extracting(StorageFile::size).isEqualTo(1L);
        }
    }

    @SqlContractTest
    void aConfirmationWithoutReservationOrRecordIsExpired(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            assertThat(storage.metadata().confirmUpload(storage.server(), KEY, 42, "image/png")).isEqualTo(UploadConfirmation.EXPIRED);
            assertThat(storage.database().dsl().fetchCount(STORAGE_FILES)).isZero();
        }
    }

    @SqlContractTest
    void aReservationOfAnAlreadyRecordedFileIsConsumedWithoutCountingTwice(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.file(KEY, 7);
            storage.reservation(KEY, 7);
            assertThat(storage.total().bytes()).isEqualTo(7);

            UploadConfirmation confirmation = storage.metadata().confirmUpload(storage.server(), KEY, 7, "image/png");

            assertThat(confirmation).isEqualTo(UploadConfirmation.ALREADY_RECORDED);
            assertThat(storage.hasReservation(KEY)).isFalse();
            assertThat(storage.total().bytes()).isEqualTo(7);
        }
    }

    @SqlContractTest
    void aFailedRecordRollsTheClaimBack(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.reservation(KEY, 42);
            assertThat(storage.total().bytes()).isZero();

            assertThatThrownBy(() -> storage.metadata().confirmUpload(storage.server(), KEY, 42, "x".repeat(300)))
                .isInstanceOf(ValueTooLongException.class);

            assertThat(storage.hasReservation(KEY)).isTrue();
            assertThat(storage.hasFile(KEY)).isFalse();
            assertThat(storage.total().bytes()).isZero();
        }
    }

    @SqlContractTest
    void existingKeysAndConfirmedFilesIgnoreUnknownKeys(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.file(KEY, 3);
            List<String> keys = List.of(KEY, "modl_instance/missing");

            assertThat(storage.metadata().existingKeys(storage.server(), keys)).containsExactly(KEY);
            assertThat(storage.metadata().findConfirmedFiles(storage.server(), keys)).containsOnlyKeys(KEY);
        }
    }

    private static void confirm(StorageContractFixture storage, String key) {
        storage.reservation(key, 1);
        assertThat(storage.metadata().confirmUpload(storage.server(), key, 1, "application/octet-stream"))
            .isEqualTo(UploadConfirmation.RECORDED);
    }
}
