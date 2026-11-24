package gg.modl.backend.storage.backend;

import static gg.modl.backend.storage.backend.StorageTestBackends.put;
import static gg.modl.backend.storage.backend.StorageTestBackends.read;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.storage.OwnershipMarker;
import gg.modl.backend.storage.StorageNamespace;
import gg.modl.backend.storage.StorageType;
import gg.modl.backend.storage.local.LocalObjectStorage;
import gg.modl.backend.storage.local.UploadSigner;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageTransferJobTest {
    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String A = StorageNamespace.KEY_PREFIX + "tickets/T-1/a.txt";
    private static final String B = StorageNamespace.KEY_PREFIX + "evidence/E-1/b.txt";

    @TempDir
    Path sourceRoot;
    @TempDir
    Path targetRoot;

    private LocalObjectStorage source;
    private LocalObjectStorage target;
    private InMemoryStorageTransfers transfers;
    private final List<Duration> sleeps = new ArrayList<>();

    @BeforeEach
    void setUp() {
        source = StorageTestBackends.local(sourceRoot);
        target = StorageTestBackends.local(targetRoot);
        transfers = new InMemoryStorageTransfers();
        put(source, A, "alpha");
        put(source, B, "beta");
        source.writeOwnershipMarker(StorageNamespace.NAME, "owner");
    }

    @Test
    void everyFileIsCopiedVerifiedAndCounted() throws InterruptedException {
        StorageTransfer done = run(startedLongAgo(), () -> false).orElseThrow();

        assertThat(sleeps).isEmpty();
        assertThat(done.state()).isEqualTo(StorageTransferState.DONE);
        assertThat(done.counts().copiedObjects()).isEqualTo(2);
        assertThat(done.counts().copiedBytes()).isEqualTo("alpha".length() + "beta".length());
        assertThat(read(target, A)).isEqualTo("alpha");
        assertThat(read(target, B)).isEqualTo("beta");
        assertThat(target.exists(OwnershipMarker.key(StorageNamespace.NAME))).isFalse();
        assertThat(source.exists(A)).isTrue();
    }

    @Test
    void aResumedCopySkipsWhatAlreadyArrived() throws InterruptedException {
        put(target, A, "alpha");

        StorageTransfer done = run(startedLongAgo(), () -> false).orElseThrow();

        assertThat(done.counts().copiedObjects()).isEqualTo(1);
        assertThat(done.counts().skippedObjects()).isEqualTo(1);
    }

    @Test
    void aDifferentFileUnderTheSameNameFailsTheCopyInsteadOfBeingOverwritten() throws InterruptedException {
        put(target, A, "something else");

        StorageTransfer failed = run(startedLongAgo(), () -> false).orElseThrow();

        assertThat(failed.state()).isEqualTo(StorageTransferState.FAILED);
        assertThat(failed.counts().failedObjects()).isEqualTo(1);
        assertThat(failed.lastError()).contains(A);
        assertThat(read(target, A)).isEqualTo("something else");
    }

    @Test
    void aFreshCopyWithNoOpenUploadsFinishesOnceTheSwitchHasSettled() throws InterruptedException {
        StorageTransfer fresh = StorageTransfer.started("t-1", StorageType.LOCAL, StorageType.LOCAL, NOW);
        transfers.insert(fresh);
        AdvancingClock clock = new AdvancingClock(NOW);
        String late = StorageNamespace.KEY_PREFIX + "tickets/T-9/late.txt";

        StorageTransfer done = new StorageTransferJob(route(), fresh, transfers, cutoff -> false, clock, () -> false, duration -> {
            sleeps.add(duration);
            put(source, late, "late");
            clock.advance(duration);
        }).run().orElseThrow();

        assertThat(sleeps).containsExactly(StorageTransferJob.GRANTS_BEFORE_SWITCH);
        assertThat(done.state()).isEqualTo(StorageTransferState.DONE);
        assertThat(read(target, late)).isEqualTo("late");
    }

    @Test
    void theCountsOfEveryPassAddUpInsteadOfStartingOver() throws InterruptedException {
        StorageTransfer fresh = StorageTransfer.started("t-1", StorageType.LOCAL, StorageType.LOCAL, NOW);
        transfers.insert(fresh);
        AdvancingClock clock = new AdvancingClock(NOW);
        String late = StorageNamespace.KEY_PREFIX + "tickets/T-9/late.txt";

        StorageTransfer done = new StorageTransferJob(route(), fresh, transfers, cutoff -> false, clock, () -> false, duration -> {
            put(source, late, "late");
            clock.advance(duration);
        }).run().orElseThrow();

        assertThat(done.counts().copiedObjects()).isEqualTo(3);
        assertThat(done.counts().skippedObjects()).isZero();
    }

    @Test
    void theLastPassWaitsUntilEveryUploadGrantedBeforeTheSwitchHasLanded() throws InterruptedException {
        StorageTransfer fresh = StorageTransfer.started("t-1", StorageType.LOCAL, StorageType.LOCAL, NOW);
        transfers.insert(fresh);
        AdvancingClock clock = new AdvancingClock(NOW);
        String slow = StorageNamespace.KEY_PREFIX + "tickets/T-8/slow.txt";
        List<Instant> asked = new ArrayList<>();
        PreSwitchUploads uploads = cutoff -> {
            asked.add(cutoff);
            return clock.instant().isBefore(NOW.plus(Duration.ofMinutes(25)));
        };

        StorageTransfer done = new StorageTransferJob(route(), fresh, transfers, uploads, clock, () -> false, duration -> {
            sleeps.add(duration);
            clock.advance(duration);
            if (!clock.instant().isBefore(NOW.plus(Duration.ofMinutes(24))) && !source.exists(slow)) {
                put(source, slow, "slow");
            }
        }).run().orElseThrow();

        assertThat(done.state()).isEqualTo(StorageTransferState.DONE);
        assertThat(read(target, slow)).isEqualTo("slow");
        assertThat(sleeps).contains(StorageTransferJob.UPLOAD_RECHECK);
        assertThat(asked).allSatisfy(cutoff -> assertThat(cutoff).isEqualTo(NOW.plus(StorageTransferJob.GRANTS_BEFORE_SWITCH)));
    }

    @Test
    void aFileDeletedWhileItWasBeingCopiedDoesNotComeBackInTheNewStorage() throws InterruptedException {
        LocalObjectStorage deletingSource = new LocalObjectStorage(sourceRoot, new UploadSigner(() -> "signing-key"), CLOCK) {
            @Override
            public InputStream read(String key) {
                InputStream data = super.read(key);
                if (key.equals(A)) {
                    try {
                        byte[] bytes = data.readAllBytes();
                        data.close();
                        delete(A);
                        return new ByteArrayInputStream(bytes);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                return data;
            }
        };
        StorageRoute.Migrating deleting = new StorageRoute.Migrating(StorageType.LOCAL, deletingSource, StorageType.LOCAL, target);

        StorageTransfer done = new StorageTransferJob(deleting, startedLongAgo(), transfers, cutoff -> false, CLOCK, () -> false,
            sleeps::add).run().orElseThrow();

        assertThat(done.state()).isEqualTo(StorageTransferState.DONE);
        assertThat(target.exists(A)).isFalse();
        assertThat(read(target, B)).isEqualTo("beta");
    }

    @Test
    void aCopyThatThrowsEndsAsFailedSoItCanBeRetried() throws InterruptedException {
        PreSwitchUploads unavailable = cutoff -> {
            throw new IllegalStateException("database briefly unavailable");
        };

        StorageTransfer failed = new StorageTransferJob(route(), startedLongAgo(), transfers, unavailable, CLOCK, () -> false,
            sleeps::add).run().orElseThrow();

        assertThat(failed.state()).isEqualTo(StorageTransferState.FAILED);
        assertThat(failed.lastError()).contains("database briefly unavailable");
        assertThat(transfers.latest()).hasValueSatisfying(row -> assertThat(row.state()).isEqualTo(StorageTransferState.FAILED));
    }

    @Test
    void aStoppedCopyReportsNothingAndCanResumeLater() throws InterruptedException {
        assertThat(run(startedLongAgo(), () -> true)).isEmpty();
        assertThat(target.exists(A)).isFalse();
    }

    @Test
    void progressOfACancelledTransferIsNotWrittenBack() throws InterruptedException {
        StorageTransfer transfer = startedLongAgo();
        transfers.save(transfer.finished(StorageTransferState.FAILED, NOW));
        int savesBefore = transfers.saves();

        run(transfer, () -> false);

        assertThat(transfers.latest()).hasValueSatisfying(row -> assertThat(row.state()).isEqualTo(StorageTransferState.FAILED));
        assertThat(transfers.saves()).isEqualTo(savesBefore);
    }

    private StorageTransfer startedLongAgo() {
        StorageTransfer transfer = StorageTransfer.started("t-1", StorageType.LOCAL, StorageType.LOCAL, NOW.minus(Duration.ofHours(1)));
        transfers.insert(transfer);
        return transfer;
    }

    private Optional<StorageTransfer> run(StorageTransfer transfer, BooleanSupplier stopped)
        throws InterruptedException {
        return new StorageTransferJob(route(), transfer, transfers, cutoff -> false, CLOCK, stopped, sleeps::add).run();
    }

    private StorageRoute.Migrating route() {
        return new StorageRoute.Migrating(StorageType.LOCAL, source, StorageType.LOCAL, target);
    }

    private static final class AdvancingClock extends Clock {
        private Instant now;

        private AdvancingClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
