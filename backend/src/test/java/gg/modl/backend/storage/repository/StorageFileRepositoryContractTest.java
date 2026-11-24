package gg.modl.backend.storage.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.database.ValueTooLongException;
import gg.modl.backend.storage.StorageContractFixture;
import gg.modl.backend.storage.UploadOrigin;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.io.TempDir;

class StorageFileRepositoryContractTest {
    private static final Instant NOW = StorageContractFixture.START;
    private static final String KEY = "modl_instance/evidence/PUN-1/clip.mp4";

    @TempDir
    Path directory;

    @SqlContractTest
    void aFileRoundTripsWithUnicodeAndNullableColumns(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            StorageFile full = new StorageFile(KEY, "clip é漢😀.mp4", 7_000_000_000L, "video/mp4", "evidence", NOW);
            StorageFile sparse = new StorageFile(KEY + ".bin", null, 0L, null, null, NOW);

            assertThat(storage.files().insertIfAbsent(full, UploadOrigin.AUTHENTICATED, null)).isTrue();
            assertThat(storage.files().insertIfAbsent(sparse, UploadOrigin.ANONYMOUS, NOW)).isTrue();

            assertThat(storage.files().findByKey(KEY)).contains(full);
            assertThat(storage.files().findByKeys(List.of(sparse.key(), KEY, "modl_instance/missing"))).containsExactly(full, sparse);
        }
    }

    @SqlContractTest
    void aSecondInsertOfAKeyKeepsTheFirstRow(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.file(KEY, 10, NOW, NOW.plusSeconds(60));

            boolean inserted = storage.files().insertIfAbsent(
                new StorageFile(KEY, "other", 99, "image/gif", "other", NOW), UploadOrigin.ANONYMOUS, null);

            assertThat(inserted).isFalse();
            assertThat(storage.files().findByKey(KEY)).get().extracting(StorageFile::size).isEqualTo(10L);
            assertThat(storage.unattachedExpiryOf(KEY)).isEqualTo(NOW.plusSeconds(60));
            assertThat(storage.fileOriginOf(KEY)).isEqualTo(UploadOrigin.AUTHENTICATED);
        }
    }

    @SqlContractTest
    void keysCompareByteForByte(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.file(KEY, 10);

            assertThat(storage.files().findByKey(KEY.toUpperCase(Locale.ROOT))).isEmpty();
            assertThat(storage.files().findByKey(KEY + " ")).isEmpty();

            storage.files().deleteByKeys(List.of(KEY + " "));

            assertThat(storage.hasFile(KEY)).isTrue();
        }
    }

    @SqlContractTest
    void aKeyTooLongToStoreIsAbsentForLookupsAndRefusedForInserts(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            String widest = "😀".repeat(512);
            String tooLong = "k".repeat(513);
            storage.file(widest, 1);

            assertThat(storage.files().findByKey(widest)).isPresent();
            assertThat(storage.files().findByKey(tooLong)).isEmpty();
            assertThat(storage.files().markAttached(List.of(tooLong))).isZero();
            assertThatThrownBy(() -> storage.file(tooLong, 1)).isInstanceOf(ValueTooLongException.class);
        }
    }

    @SqlContractTest
    void markAttachedClearsTheExpiryAndCountsEveryRecordedFile(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            String permanent = "modl_instance/article/a.png";
            storage.file(KEY, 1, NOW, NOW.plusSeconds(60));
            storage.file(permanent, 1);

            assertThat(storage.files().markAttached(List.of(KEY, KEY, permanent, "modl_instance/missing"))).isEqualTo(2);
            assertThat(storage.unattachedExpiryOf(KEY)).isNull();
            assertThat(storage.files().markAttached(List.of(KEY))).isEqualTo(1);
            assertThat(storage.files().markAttached(List.of())).isZero();
        }
    }

    @SqlContractTest
    void onlyUnattachedFilesPastTheirExpiryAreFoundOldestFirst(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.file("k/b", 1, NOW, NOW.minusSeconds(5));
            storage.file("k/a", 1, NOW, NOW.minusSeconds(5));
            storage.file("k/oldest", 1, NOW, NOW.minusSeconds(9));
            storage.file("k/due-now", 1, NOW, NOW);
            storage.file("k/attached", 1, NOW.minusSeconds(900_000), null);

            assertThat(findExpired(storage, 10)).containsExactly("k/oldest", "k/a", "k/b");
            assertThat(findExpired(storage, 2)).containsExactly("k/oldest", "k/a");
        }
    }

    @SqlContractTest
    void anAttachmentWaitsForTheSweepThatSelectedTheFileAndThenFindsItGone(DialectTarget target) throws Exception {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory);
             ExecutorService submitter = Executors.newSingleThreadExecutor()) {
            storage.file(KEY, 1, NOW, NOW.minusSeconds(1));
            CountDownLatch started = new CountDownLatch(1);
            AtomicReference<Future<Integer>> attachment = new AtomicReference<>();

            storage.database().unitOfWork().write(() -> {
                List<String> expired = storage.files().findUnattachedExpired(NOW, 10);
                attachment.set(submitter.submit(() -> {
                    started.countDown();
                    return storage.files().markAttached(List.of(KEY));
                }));
                assertThatThrownBy(() -> {
                    started.await();
                    attachment.get().get(300, TimeUnit.MILLISECONDS);
                }).isInstanceOf(TimeoutException.class);
                storage.files().deleteByKeys(expired);
            });

            assertThat(attachment.get().get(10, TimeUnit.SECONDS)).isZero();
            assertThat(storage.hasFile(KEY)).isFalse();
        }
    }

    @SqlContractTest
    void deletionAndLookupWorkBeyondOneChunk(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            List<String> keys = IntStream.range(0, 501).mapToObj(index -> "k/" + index).toList();
            storage.database().unitOfWork().write(() -> keys.forEach(key -> storage.file(key, 2)));

            assertThat(storage.files().findByKeys(keys)).hasSize(501);
            assertThat(storage.files().sumTotalSize()).isEqualTo(1002);

            storage.files().deleteByKeys(keys);

            assertThat(storage.files().hasAnyFile()).isFalse();
        }
    }

    @SqlContractTest
    void totalsAreZeroWithoutFiles(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            assertThat(storage.files().sumTotalSize()).isZero();
            assertThat(storage.files().sumSizeSince(UploadOrigin.ANONYMOUS, NOW)).isZero();
            assertThat(storage.files().hasAnyFile()).isFalse();
        }
    }

    @SqlContractTest
    void totalsExceedTheIntegerRange(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.file("k/1", 6_000_000_000L);
            storage.file("k/2", 5_000_000_001L);

            assertThat(storage.files().sumTotalSize()).isEqualTo(11_000_000_001L);
            assertThat(storage.files().hasAnyFile()).isTrue();
        }
    }

    @SqlContractTest
    void prefixUsageFollowsByteOrder(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.file("modl_instance/tickets/new/a", 1);
            storage.file("modl_instance/tickets/new/😀", 2);
            storage.file("modl_instance/tickets/new/%_!", 4);
            storage.file("modl_instance/tickets/New/a", 32);
            storage.file("modl_instance/tickets/new0", 64);
            storage.file("modl_instance/tickets/new", 128);
            storage.file("modlXinstance/tickets/new/a", 256);

            assertThat(storage.files().usageUnder(List.of("modl_instance/tickets/new/"))).isEqualTo(new PrefixUsage(3, 7));
        }
    }

    @SqlContractTest
    void usageUnderSeveralPrefixesCountsEachFileOnceAndNoPrefixCountsNothing(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.file("modl_instance/replays/a", 1);
            storage.file("modl_instance/replay/b", 2);
            storage.file("modl_instance/evidence/c", 4);

            assertThat(storage.files().usageUnder(List.of("modl_instance/replays/", "modl_instance/replay/")))
                .isEqualTo(new PrefixUsage(2, 3));
            assertThat(storage.files().usageUnder(List.of())).isEqualTo(new PrefixUsage(0, 0));
        }
    }

    @SqlContractTest
    void originTotalsCountOnlyThatOriginInsideTheWindow(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.file("modl_instance/tickets/new/a", 1, NOW, null, UploadOrigin.ANONYMOUS);
            storage.file("modl_instance/appeal/new/b", 2, NOW.plusSeconds(5), null, UploadOrigin.ANONYMOUS);
            storage.file("modl_instance/tickets/new/old", 4, NOW.minusMillis(1), null, UploadOrigin.ANONYMOUS);
            storage.file("modl_instance/tickets/T-1/staff.png", 8, NOW, null, UploadOrigin.AUTHENTICATED);
            storage.file("modl_instance/evidence/PUN-1/clip.mp4", 6_000_000_000L, NOW, null, UploadOrigin.AUTHENTICATED);

            assertThat(storage.files().sumSizeSince(UploadOrigin.ANONYMOUS, NOW)).isEqualTo(3);
            assertThat(storage.files().sumSizeSince(UploadOrigin.AUTHENTICATED, NOW)).isEqualTo(6_000_000_008L);
        }
    }

    private static List<String> findExpired(StorageContractFixture storage, int limit) {
        return storage.database().unitOfWork().write(() -> storage.files().findUnattachedExpired(NOW, limit));
    }
}
