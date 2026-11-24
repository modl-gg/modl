package gg.modl.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.ratelimit.BucketPool;
import gg.modl.backend.storage.repository.StorageReservation;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class StorageLimitPolicyTest {
    private static final long MAX_UPLOAD_MB = 100;
    private static final Instant NOW = StorageContractFixture.START;
    private static final Instant UPLOAD_DEADLINE = NOW.plusSeconds(900);
    private static final long GIBIBYTE = 1024L * 1024 * 1024;
    private static final String KEY = "modl_instance/evidence/PUN-1/clip.mp4";
    private static final String TEMPORARY_KEY = "modl_instance/tickets/new/clip.mp4";
    private static final String CLIENT = "203.0.113.7";

    @TempDir
    Path directory;

    @SqlContractTest
    void reservationsAreRecordedUntilTheConfirmationGraceAfterTheUploadDeadline(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory, new RetryInjection())) {
            policy(storage, 0L).reserve(KEY, 2048L, UPLOAD_DEADLINE);

            assertThat(storage.reservations().claim(KEY)).contains(new StorageReservation(
                KEY, 2048L, false, UPLOAD_DEADLINE.plus(StorageLimitPolicy.CONFIRMATION_GRACE), NOW, UploadOrigin.AUTHENTICATED));
        }
    }

    @SqlContractTest
    void confirmedAndOutstandingBytesCountAgainstTheTotalStorageLimit(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.file("modl_instance/replays/a", GIBIBYTE / 2);
            storage.reservation("modl_instance/replays/b", GIBIBYTE / 2 - 100);
            StorageLimitPolicy policy = policy(storage, 1L);

            assertThatThrownBy(() -> policy.reserve(KEY, 101L, UPLOAD_DEADLINE)).isInstanceOf(StorageLimitExceededException.class);
            assertThat(storage.hasReservation(KEY)).isFalse();

            policy.reserve(KEY, 100L, UPLOAD_DEADLINE);

            assertThat(storage.hasReservation(KEY)).isTrue();
        }
    }

    @SqlContractTest
    void onlyInFlightReservationsCountAgainstTheDiskCapacity(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.reservation("modl_instance/replays/landed", 4000);
            storage.reservations().markLanded("modl_instance/replays/landed");
            storage.reservation("modl_instance/replays/in-flight", 5000);
            when(storage.objectStorage().hasCapacityFor(7048L)).thenReturn(false);

            assertThatThrownBy(() -> policy(storage, 0L).reserve(KEY, 2048L, UPLOAD_DEADLINE))
                .isInstanceOf(StorageLimitExceededException.class);

            verify(storage.objectStorage()).hasCapacityFor(7048L);
            assertThat(storage.hasReservation(KEY)).isFalse();
        }
    }

    @SqlContractTest
    void unlimitedStorageNeverReadsTheConfirmedTotal(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            ConfirmedStorageTotal unread = mock(ConfirmedStorageTotal.class);

            policy(storage, unread, 0L).reserve(KEY, 2048L, UPLOAD_DEADLINE);

            verify(unread, never()).bytes();
            verify(storage.objectStorage()).hasCapacityFor(anyLong());
        }
    }

    @SqlContractTest
    void publicUploadsShareOneWindowAcrossEveryPublicRecord(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.file("modl_instance/tickets/new/recent", 30, NOW.minus(Duration.ofHours(6)), null, UploadOrigin.ANONYMOUS);
            storage.file("modl_instance/tickets/T-1/recent", 30, NOW.minus(Duration.ofHours(1)), null, UploadOrigin.ANONYMOUS);
            storage.file("modl_instance/tickets/new/old", 500, NOW.minus(Duration.ofHours(6)).minusMillis(1), null, UploadOrigin.ANONYMOUS);
            storage.file("modl_instance/evidence/PUN-1/kept", 500);
            storage.reservation("modl_instance/appeal/A-1/pending", 30, UploadOrigin.ANONYMOUS);
            StorageLimitPolicy policy = policy(storage, 0L);

            assertThatThrownBy(() -> policy.reservePublic(TEMPORARY_KEY, 11L, UPLOAD_DEADLINE, PublicUpload.unattached(CLIENT)))
                .isInstanceOf(TemporaryUploadLimitExceededException.class);
            assertThatThrownBy(() -> policy.reservePublic("modl_instance/tickets/T-2/clip.mp4", 11L, UPLOAD_DEADLINE,
                PublicUpload.forRecord(CLIENT, "modl_instance/tickets/T-2/"))).isInstanceOf(TemporaryUploadLimitExceededException.class);

            policy.reservePublic(TEMPORARY_KEY, 10L, UPLOAD_DEADLINE, PublicUpload.unattached(CLIENT));

            assertThat(storage.hasReservation(TEMPORARY_KEY)).isTrue();
        }
    }

    @SqlContractTest
    void staffUploadsUnderPublicFoldersDoNotSpendThePublicWindow(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.file("modl_instance/tickets/T-1/staff-reply.png", 90);
            storage.file("modl_instance/appeal/A-1/staff-note.png", 90);
            storage.reservation("modl_instance/tickets/T-2/staff-pending.png", 90);
            StorageLimitPolicy policy = policy(storage, 0L);

            policy.reservePublic(TEMPORARY_KEY, 100L, UPLOAD_DEADLINE, PublicUpload.unattached(CLIENT));

            assertThat(storage.reservations().claim(TEMPORARY_KEY)).get()
                .extracting(StorageReservation::origin).isEqualTo(UploadOrigin.ANONYMOUS);
        }
    }

    @SqlContractTest
    void aConfirmedAnonymousUploadKeepsCountingAgainstThePublicWindow(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            StorageLimitPolicy policy = policy(storage, 0L);
            policy.reservePublic(TEMPORARY_KEY, 60L, UPLOAD_DEADLINE, PublicUpload.unattached(CLIENT));

            storage.metadata().confirmUpload(storage.server(), TEMPORARY_KEY, 60L, "video/mp4");

            assertThat(storage.fileOriginOf(TEMPORARY_KEY)).isEqualTo(UploadOrigin.ANONYMOUS);
            assertThatThrownBy(() -> policy.reservePublic("modl_instance/appeal/new/clip.mp4", 41L, UPLOAD_DEADLINE,
                PublicUpload.unattached("198.51.100.4"))).isInstanceOf(TemporaryUploadLimitExceededException.class);
        }
    }

    @SqlContractTest
    void aPublicRecordAcceptsOnlyABoundedNumberOfFiles(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            for (int file = 0; file < StorageLimitPolicy.MAX_PUBLIC_FILES_PER_RECORD; file++) {
                storage.file("modl_instance/tickets/T-1/file-" + file, 1, NOW.minus(Duration.ofDays(1)), null);
            }
            StorageLimitPolicy policy = largePolicy(storage);

            assertThatThrownBy(() -> policy.reservePublic("modl_instance/tickets/T-1/one-more", 1L, UPLOAD_DEADLINE,
                PublicUpload.forRecord(CLIENT, "modl_instance/tickets/T-1/"))).isInstanceOf(TemporaryUploadLimitExceededException.class);
            policy.reservePublic("modl_instance/tickets/T-10/first", 1L, UPLOAD_DEADLINE,
                PublicUpload.forRecord(CLIENT, "modl_instance/tickets/T-10/"));

            assertThat(storage.hasReservation("modl_instance/tickets/T-10/first")).isTrue();
        }
    }

    @SqlContractTest
    void oneClientNetworkGetsOnlyASmallShareOfThePublicWindow(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            StorageLimitPolicy policy = largePolicy(storage);
            long fortyMegabytes = 40L * 1024 * 1024;

            policy.reservePublic("modl_instance/tickets/new/a", fortyMegabytes, UPLOAD_DEADLINE, PublicUpload.unattached(CLIENT));
            assertThatThrownBy(() -> policy.reservePublic("modl_instance/tickets/new/b", fortyMegabytes, UPLOAD_DEADLINE,
                PublicUpload.unattached(CLIENT))).isInstanceOf(TemporaryUploadLimitExceededException.class);
            policy.reservePublic("modl_instance/tickets/new/c", fortyMegabytes, UPLOAD_DEADLINE,
                PublicUpload.unattached("198.51.100.4"));

            assertThat(StorageLimitPolicy.PUBLIC_CLIENT_BUDGET.capacity() * 1024L * 1024 * 4)
                .isLessThanOrEqualTo(DataSize.ofMegabytes(512).toBytes());
            assertThat(storage.hasReservation("modl_instance/tickets/new/b")).isFalse();
            assertThat(storage.hasReservation("modl_instance/tickets/new/c")).isTrue();
        }
    }

    @SqlContractTest
    void aRefusedPublicUploadDoesNotSpendTheClientsBudget(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            StorageLimitPolicy policy = largePolicy(storage);
            long fortyMegabytes = 40L * 1024 * 1024;
            when(storage.objectStorage().hasCapacityFor(anyLong())).thenReturn(false);

            for (int attempt = 0; attempt < 3; attempt++) {
                String key = "modl_instance/tickets/new/refused-" + attempt;
                assertThatThrownBy(() -> policy.reservePublic(key, fortyMegabytes, UPLOAD_DEADLINE, PublicUpload.unattached(CLIENT)))
                    .isInstanceOf(StorageLimitExceededException.class);
            }
            when(storage.objectStorage().hasCapacityFor(anyLong())).thenReturn(true);

            policy.reservePublic("modl_instance/tickets/new/admitted", fortyMegabytes, UPLOAD_DEADLINE, PublicUpload.unattached(CLIENT));

            assertThat(storage.hasReservation("modl_instance/tickets/new/admitted")).isTrue();
        }
    }

    @SqlContractTest
    void regularReservationsIgnoreTheTemporaryBudget(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.file("modl_instance/tickets/new/full", 100);

            policy(storage, 0L).reserve(KEY, 2048L, UPLOAD_DEADLINE);

            assertThat(storage.hasReservation(KEY)).isTrue();
        }
    }

    private static StorageLimitPolicy policy(StorageContractFixture storage, long maxStorageGb) {
        return policy(storage, storage.total(), maxStorageGb);
    }

    private static StorageLimitPolicy policy(StorageContractFixture storage, ConfirmedStorageTotal total, long maxStorageGb) {
        return new StorageLimitPolicy(UploadLimits.of(MAX_UPLOAD_MB, maxStorageGb), total, storage.files(), storage.reservations(),
            storage.objectStorage(),
            storage.database().unitOfWork(), storage.database().clock(), new BucketPool(), DataSize.ofBytes(100), Duration.ofHours(6));
    }

    private static StorageLimitPolicy largePolicy(StorageContractFixture storage) {
        return new StorageLimitPolicy(UploadLimits.of(MAX_UPLOAD_MB, 0L), storage.total(), storage.files(),
            storage.reservations(), storage.objectStorage(), storage.database().unitOfWork(), storage.database().clock(),
            new BucketPool(), DataSize.ofGigabytes(10), Duration.ofHours(6));
    }
}
