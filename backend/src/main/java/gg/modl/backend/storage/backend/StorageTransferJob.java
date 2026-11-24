package gg.modl.backend.storage.backend;

import gg.modl.backend.infrastructure.util.FailureDescriptions;
import gg.modl.backend.storage.ListedObject;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.OwnershipMarker;
import gg.modl.backend.storage.StorageLimitExceededException;
import gg.modl.backend.storage.StorageNamespace;
import gg.modl.backend.storage.StoredObject;
import gg.modl.backend.storage.StoredObjectExistsException;
import gg.modl.backend.storage.backend.StorageTransfer.TransferCounts;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

@Slf4j
final class StorageTransferJob {
    static final Duration UPLOAD_RECHECK = Duration.ofMinutes(1);
    static final Duration GRANTS_BEFORE_SWITCH = Duration.ofMinutes(1);
    private static final int PROGRESS_EVERY_OBJECTS = 50;
    private static final Duration PROGRESS_EVERY = Duration.ofSeconds(5);

    private final StorageRoute.Migrating route;
    private final StorageTransferRepository transfers;
    private final PreSwitchUploads uploads;
    private final Clock clock;
    private final BooleanSupplier stopped;
    private final Sleeper sleeper;
    private StorageTransfer transfer;
    private @Nullable Instant lastSaved;

    StorageTransferJob(StorageRoute.Migrating route, StorageTransfer transfer, StorageTransferRepository transfers,
                       PreSwitchUploads uploads, Clock clock, BooleanSupplier stopped, Sleeper sleeper) {
        this.route = route;
        this.transfer = transfer;
        this.transfers = transfers;
        this.uploads = uploads;
        this.clock = clock;
        this.stopped = stopped;
        this.sleeper = sleeper;
    }

    Optional<StorageTransfer> run() throws InterruptedException {
        try {
            return copyUntilSettled();
        } catch (RuntimeException e) {
            if (stopped.getAsBoolean()) {
                return Optional.empty();
            }
            log.error("Moving the uploaded files failed", e);
            transfer = transfer.progressed(transfer.counts(), "The copy stopped: " + FailureDescriptions.describe(e), clock.instant());
            return Optional.of(finish(StorageTransferState.FAILED));
        }
    }

    private Optional<StorageTransfer> copyUntilSettled() throws InterruptedException {
        Instant grantedBefore = transfer.startedAt().plus(GRANTS_BEFORE_SWITCH);
        TransferCounts counts = TransferCounts.NONE;
        boolean firstPass = true;
        while (!stopped.getAsBoolean()) {
            boolean lastPass = !clock.instant().isBefore(grantedBefore) && !uploads.anyOpenFrom(grantedBefore);
            counts = copyPass(counts, firstPass);
            firstPass = false;
            if (stopped.getAsBoolean()) {
                return Optional.empty();
            }
            if (counts.failedObjects() > 0) {
                return Optional.of(finish(StorageTransferState.FAILED));
            }
            if (lastPass) {
                return Optional.of(finish(StorageTransferState.DONE));
            }
            waitForPreSwitchUploads(grantedBefore);
        }
        return Optional.empty();
    }

    private void waitForPreSwitchUploads(Instant grantedBefore) throws InterruptedException {
        Instant now = clock.instant();
        if (now.isBefore(grantedBefore)) {
            sleeper.sleep(Duration.between(now, grantedBefore));
        }
        while (!stopped.getAsBoolean() && uploads.anyOpenFrom(grantedBefore)) {
            sleeper.sleep(UPLOAD_RECHECK);
        }
    }

    private TransferCounts copyPass(TransferCounts carried, boolean countSkipped) {
        TransferCounts counts = carried;
        String error = null;
        try (Stream<ListedObject> objects = route.source().list(StorageNamespace.KEY_PREFIX)) {
            Iterator<ListedObject> iterator = objects.iterator();
            while (iterator.hasNext() && !stopped.getAsBoolean()) {
                ListedObject listed = iterator.next();
                if (OwnershipMarker.isMarkerKey(listed.key())) {
                    continue;
                }
                CopyResult result = copy(listed);
                if (result.outcome() != CopyResult.Outcome.SKIPPED || countSkipped) {
                    counts = result.applyTo(counts);
                }
                if (result.error() != null) {
                    error = result.error();
                }
                if (result.stopsThePass()) {
                    break;
                }
                saveProgressIfDue(counts, error);
            }
        } catch (RuntimeException e) {
            log.warn("Listing the files to move failed", e);
            counts = counts.failed();
            error = "Listing the files failed: " + FailureDescriptions.describe(e);
        }
        transfer = transfer.progressed(counts, error, clock.instant());
        save();
        return counts;
    }

    private CopyResult copy(ListedObject listed) {
        String key = listed.key();
        try {
            Optional<StoredObject> existing = route.target().stat(key);
            if (existing.isPresent()) {
                return existing.get().size() == listed.size()
                    ? CopyResult.SKIPPED
                    : CopyResult.failed(key + " already exists in the new storage with a different size", false);
            }
            Optional<StoredObject> source = route.source().stat(key);
            if (source.isEmpty()) {
                return CopyResult.SKIPPED;
            }
            write(route.source(), route.target(), source.get());
            if (!route.source().exists(key)) {
                route.target().delete(key);
                return CopyResult.SKIPPED;
            }
            return CopyResult.copied(source.get().size());
        } catch (StorageLimitExceededException e) {
            return CopyResult.failed("The new storage is full: " + e.getMessage(), true);
        } catch (StoredObjectExistsException e) {
            return CopyResult.SKIPPED;
        } catch (RuntimeException e) {
            log.debug("Copying {} failed", key, e);
            return CopyResult.failed(key + ": " + FailureDescriptions.describe(e), false);
        }
    }

    private static void write(ObjectStorage source, ObjectStorage target, StoredObject object) {
        try (InputStream data = source.read(object.key())) {
            target.write(object.key(), data, object.size(), object.contentType());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void saveProgressIfDue(TransferCounts counts, @Nullable String error) {
        Instant now = clock.instant();
        if (lastSaved == null || counts.handled() % PROGRESS_EVERY_OBJECTS == 0 || !now.isBefore(lastSaved.plus(PROGRESS_EVERY))) {
            transfer = transfer.progressed(counts, error, now);
            save();
        }
    }

    private StorageTransfer finish(StorageTransferState outcome) {
        transfer = transfer.finished(outcome, clock.instant());
        save();
        return transfer;
    }

    private void save() {
        transfers.saveWhileRunning(transfer);
        lastSaved = clock.instant();
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private record CopyResult(Outcome outcome, long bytes, @Nullable String error, boolean stopsThePass) {
        static final CopyResult SKIPPED = new CopyResult(Outcome.SKIPPED, 0, null, false);

        static CopyResult copied(long bytes) {
            return new CopyResult(Outcome.COPIED, bytes, null, false);
        }

        static CopyResult failed(String error, boolean stopsThePass) {
            return new CopyResult(Outcome.FAILED, 0, error, stopsThePass);
        }

        TransferCounts applyTo(TransferCounts counts) {
            return switch (outcome) {
                case COPIED -> counts.copied(bytes);
                case SKIPPED -> counts.skipped();
                case FAILED -> counts.failed();
            };
        }

        private enum Outcome {
            COPIED,
            SKIPPED,
            FAILED
        }
    }
}
