package gg.modl.backend.storage.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.database.DuplicateRowException;
import gg.modl.backend.storage.ReservedBytes;
import gg.modl.backend.storage.StorageContractFixture;
import gg.modl.backend.storage.UploadOrigin;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.io.TempDir;

class StorageReservationRepositoryContractTest {
    private static final Instant NOW = StorageContractFixture.START;
    private static final String KEY = "modl_instance/tickets/new/shot.png";
    private static final int CLAIMANTS = 8;

    @TempDir
    Path directory;

    @SqlContractTest
    void aClaimReturnsTheStoredReservationAndConsumesIt(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            StorageReservation reservation = new StorageReservation(KEY, 5_000_000_000L, true, NOW.plusSeconds(1), NOW, UploadOrigin.ANONYMOUS);
            storage.reservations().reserve(reservation);

            assertThat(storage.reservations().claim(KEY)).contains(reservation);
            assertThat(storage.hasReservation(KEY)).isFalse();
            assertThat(storage.reservations().claim(KEY)).isEmpty();
        }
    }

    @SqlContractTest
    void onlyReservationsGrantedBeforeTheCutoffCount(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.reservations().reserve(new StorageReservation(KEY, 10, false, NOW.plusSeconds(900), NOW, UploadOrigin.ANONYMOUS));

            assertThat(storage.reservations().anyCreatedBefore(NOW)).isFalse();
            assertThat(storage.reservations().anyCreatedBefore(NOW.plusMillis(1))).isTrue();
            storage.reservations().claim(KEY);
            assertThat(storage.reservations().anyCreatedBefore(NOW.plusSeconds(60))).isFalse();
        }
    }

    @SqlContractTest
    void onlyOneOfEightConcurrentClaimsWins(DialectTarget target) throws Exception {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory);
             ExecutorService claimants = Executors.newFixedThreadPool(CLAIMANTS)) {
            storage.reservation(KEY, 10);
            List<Callable<Optional<StorageReservation>>> claims = IntStream.range(0, CLAIMANTS)
                .<Callable<Optional<StorageReservation>>>mapToObj(index -> () -> storage.reservations().claim(KEY))
                .toList();

            int winners = 0;
            for (Future<Optional<StorageReservation>> claim : claimants.invokeAll(claims)) {
                winners += claim.get().isPresent() ? 1 : 0;
            }

            assertThat(winners).isEqualTo(1);
        }
    }

    @SqlContractTest
    void aClaimRollsBackWithItsUnit(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.reservation(KEY, 10);

            assertThatThrownBy(() -> storage.database().unitOfWork().write(() -> {
                storage.reservations().claim(KEY);
                throw new IllegalStateException("the follow-up write failed");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(storage.hasReservation(KEY)).isTrue();
        }
    }

    @SqlContractTest
    void aKeyWithAnotherCaseOrATrailingSpaceDoesNotClaim(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.reservation(KEY, 10);

            assertThat(storage.reservations().claim(KEY.toUpperCase(Locale.ROOT))).isEmpty();
            assertThat(storage.reservations().claim(KEY + " ")).isEmpty();
            assertThat(storage.reservations().claim("k".repeat(1024))).isEmpty();
            assertThat(storage.hasReservation(KEY)).isTrue();
        }
    }

    @SqlContractTest
    void aSecondReservationOfAKeyIsADuplicate(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.reservation(KEY, 10);

            assertThatThrownBy(() -> storage.reservation(KEY, 20)).isInstanceOf(DuplicateRowException.class);
            assertThat(storage.reservations().sumReserved().total()).isEqualTo(10);
        }
    }

    @SqlContractTest
    void landedBytesStayReservedButAreNoLongerInFlight(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            assertThat(storage.reservations().sumReserved()).isEqualTo(ReservedBytes.NONE);
            storage.reservation(KEY, 4_000_000_000L);
            storage.reservation(KEY + "2", 300);

            assertThat(storage.reservations().markLanded(KEY)).isTrue();
            assertThat(storage.reservations().markLanded(KEY)).isFalse();
            assertThat(storage.reservations().markLanded("modl_instance/missing")).isFalse();
            assertThat(storage.reservations().sumReserved()).isEqualTo(new ReservedBytes(4_000_000_300L, 300));

            storage.reservations().markLanded(KEY + "2");

            assertThat(storage.reservations().sumReserved()).isEqualTo(new ReservedBytes(4_000_000_300L, 0));
        }
    }

    @SqlContractTest
    void reservedBytesAreSummedByOriginWhateverTheKey(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.reservation(KEY, 1, UploadOrigin.ANONYMOUS);
            storage.reservation("modl_instance/evidence/PUN-1/b", 2, UploadOrigin.ANONYMOUS);
            storage.reservation("modl_instance/tickets/T-1/staff.png", 4, UploadOrigin.AUTHENTICATED);
            storage.reservation("modl_instance/appeal/new/c", 8_000_000_000L, UploadOrigin.AUTHENTICATED);

            assertThat(storage.reservations().sumReservedBytes(UploadOrigin.ANONYMOUS)).isEqualTo(3);
            assertThat(storage.reservations().sumReservedBytes(UploadOrigin.AUTHENTICATED)).isEqualTo(8_000_000_004L);
            assertThat(storage.reservations().claim(KEY)).get().extracting(StorageReservation::origin).isEqualTo(UploadOrigin.ANONYMOUS);
        }
    }

    @SqlContractTest
    void lapsedReservationsComeBackOldestFirstWithAStableTieBreak(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            reserveUntil(storage, "k/b", NOW.minusSeconds(1));
            reserveUntil(storage, "k/a", NOW.minusSeconds(1));
            reserveUntil(storage, "k/oldest", NOW.minusSeconds(9));
            reserveUntil(storage, "k/due-now", NOW);

            assertThat(storage.reservations().findSettleableKeys(NOW, 10)).containsExactly("k/oldest", "k/a", "k/b");
            assertThat(storage.reservations().findSettleableKeys(NOW, 1)).containsExactly("k/oldest");
        }
    }

    @SqlContractTest
    void reservedKeysAreFoundAndReleasedBeyondOneChunk(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            List<String> keys = IntStream.range(0, 501).mapToObj(index -> "k/" + index).toList();
            storage.database().unitOfWork().write(() -> keys.forEach(key -> storage.reservation(key, 1)));

            assertThat(storage.reservations().findReservedKeys(keys)).containsExactlyInAnyOrderElementsOf(keys);
            assertThat(storage.reservations().release(keys)).isEqualTo(501);
            assertThat(storage.reservations().release(keys)).isZero();
            assertThat(storage.reservations().findReservedKeys(keys)).isEmpty();
        }
    }

    private static void reserveUntil(StorageContractFixture storage, String key, Instant settleAfter) {
        storage.reservations().reserve(new StorageReservation(key, 1, false, settleAfter, NOW, UploadOrigin.AUTHENTICATED));
    }
}
