package gg.modl.backend.storage.backend;

import gg.modl.backend.database.EntityIds;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.databasemove.DatabaseMoves;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.instance.PanelLocations;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.migration.service.RunningImports;
import gg.modl.backend.migration.service.ImportExclusion;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.StorageOrigins;
import gg.modl.backend.storage.StorageProperties;
import gg.modl.backend.storage.StorageType;
import gg.modl.backend.storage.repository.StorageReservationRepository;
import gg.modl.backend.storage.s3.S3ConnectionResult;
import gg.modl.backend.storage.s3.S3ConnectionTester;
import gg.modl.backend.storage.s3.S3Origins;
import gg.modl.backend.storage.service.StorageOwnerMarker;
import gg.modl.backend.storage.service.StorageOwnerMarker.Verdict;
import gg.modl.backend.update.commit.InstanceCommittedEvent;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class StorageBackendService implements StorageOrigins {
    static final String MANAGED_CODE = "STORAGE_MANAGED_BY_CONFIG";
    static final String TRANSFER_RUNNING_CODE = "STORAGE_TRANSFER_RUNNING";
    static final String NO_FAILED_TRANSFER_CODE = "STORAGE_NO_FAILED_TRANSFER";
    static final String S3_NOT_READY_CODE = "S3_NOT_READY";
    static final String NOT_OWNED_CODE = "STORAGE_NOT_OWNED";
    static final String IN_USE_CODE = "S3_BUCKET_IN_USE";
    static final String IMPORT_RUNNING_CODE = "STORAGE_IMPORT_RUNNING";
    static final String CHANGED_CODE = "STORAGE_CHANGED";
    static final String OLD_COPIES_FULL_CODE = "STORAGE_OLD_COPIES_FULL";
    static final String NO_OLD_COPIES_CODE = "STORAGE_NO_OLD_COPIES";
    static final String REMOVING_OLD_COPIES_CODE = "STORAGE_REMOVING_OLD_COPIES";
    static final String DATABASE_MOVING_CODE = "STORAGE_DATABASE_MOVING";
    private static final int KEPT_TRANSFERS = 10;
    private static final String MANAGED_MESSAGE =
        "Storage is set by MODL_STORAGE and the MODL_S3_* variables and cannot be changed from the panel";

    private final StorageProperties environment;
    private final StorageBackendSettingsStore settings;
    private final StorageBackends backends;
    private final SwitchableObjectStorage storage;
    private final StorageTransferRepository transfers;
    private final PreSwitchUploads preSwitchUploads;
    private final S3ConnectionTester tester;
    private final StorageOwnerMarker ownerMarker;
    private final OldCopyRemoval oldCopyRemoval;
    private final RunningImports imports;
    private final ImportExclusion importExclusion;
    private final DatabaseMoves databaseMoves;
    private final UnitOfWork unitOfWork;
    private final PanelActionAuditor auditor;
    private final OperatorNotice notice;
    private final Clock clock;
    private final TransferWakeup wakeup = new TransferWakeup();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(StorageBackendService::workerThread);
    private volatile boolean stopping;
    private final AtomicLong generation = new AtomicLong();
    private @Nullable Future<?> currentJob;

    public StorageBackendService(StorageProperties environment, StorageBackendSettingsStore settings, StorageBackends backends,
                                 SwitchableObjectStorage storage, StorageTransferRepository transfers,
                                 StorageReservationRepository reservations, S3ConnectionTester tester,
                                 StorageOwnerMarker ownerMarker, OldCopyRemoval oldCopyRemoval, RunningImports imports,
                                 ImportExclusion importExclusion, DatabaseMoves databaseMoves, UnitOfWork unitOfWork,
                                 PanelActionAuditor auditor, OperatorNotice notice, Clock clock) {
        this.environment = environment;
        this.settings = settings;
        this.backends = backends;
        this.storage = storage;
        this.transfers = transfers;
        this.preSwitchUploads = reservations::anyCreatedBefore;
        this.tester = tester;
        this.ownerMarker = ownerMarker;
        this.oldCopyRemoval = oldCopyRemoval;
        this.imports = imports;
        this.importExclusion = importExclusion;
        this.databaseMoves = databaseMoves;
        this.unitOfWork = unitOfWork;
        this.auditor = auditor;
        this.notice = notice;
        this.clock = clock;
    }

    private void requireSettledDatabase() {
        if (databaseMoves.isMovingDatabase()) {
            throw new ConflictException("Wait until the database is moved before changing the uploaded files", DATABASE_MOVING_CODE);
        }
    }

    public StorageBackendView view() {
        StorageBackendSettings stored = settings.current();
        if (isManaged()) {
            return new StorageBackendView(environment.configuredType().orElseThrow(), true, environment.s3(), Optional.empty(),
                transfers.latest(), stored.oldCopies().stream().map(StorageBackendAddress::kind).toList(), false);
        }
        StorageType writesTo = stored.migrationTarget().orElse(stored.active());
        return new StorageBackendView(writesTo, false, stored.s3(), stored.migrationTarget(), transfers.latest(),
            stored.oldCopies().stream().map(StorageBackendAddress::kind).toList(), oldCopyRemoval.isRemovingOldCopies());
    }

    public boolean isManaged() {
        return environment.configuredType().isPresent();
    }

    @Override
    public Set<String> origins() {
        Set<String> origins = new LinkedHashSet<>();
        StorageBackendSettings stored = settings.current();
        if (isManaged()) {
            if (environment.configuredType().orElseThrow() == StorageType.S3) {
                origins.addAll(S3Origins.of(environment.s3()));
            }
            return origins;
        }
        if (stored.usesS3() && stored.s3().complete()) {
            origins.addAll(S3Origins.of(stored.s3()));
        }
        stored.formerPublicBaseUrls().forEach(url -> S3Origins.originOf(url).ifPresent(origins::add));
        return origins;
    }

    public S3ConnectionResult test(StorageProperties.S3 requested) {
        requireUnmanaged();
        return tester.test(withStoredSecret(validated(requested)));
    }

    public StorageBackendView saveS3(Server server, String actorEmail, StorageProperties.S3 requested) {
        requireUnmanaged();
        StorageProperties.S3 connection = withStoredSecret(validated(requested));
        StorageBackendSettings before = settings.current();
        if (before.usesS3()) {
            requireReplaceable(before, connection);
        }
        synchronized (this) {
            StorageBackendSettings current = settings.current();
            if (!current.equals(before)) {
                throw changedMeanwhile();
            }
            StorageBackendSettings saved = settings.update(stored -> stored.withS3(connection));
            if (current.usesS3()) {
                StorageRoute rebuilt = StorageRoutes.initial(environment, saved, backends);
                storage.use(rebuilt);
                backends.retireUnused(rebuilt);
            }
        }
        auditor.recordStaffAction(server, actorEmail, "Saved the S3 storage connection (bucket " + connection.bucket() + ")");
        return view();
    }

    private void requireReplaceable(StorageBackendSettings current, StorageProperties.S3 connection) {
        if (current.migrationTarget().isPresent()) {
            throw new ConflictException("Wait until the running file copy finishes before changing the S3 connection",
                TRANSFER_RUNNING_CODE);
        }
        if (!current.s3().sameBucket(connection)) {
            throw new ConflictException("The files are in this bucket; move them to local storage before using another bucket",
                IN_USE_CODE);
        }
        requireReachable(connection);
    }

    public StorageBackendView switchTo(Server server, String actorEmail, StorageType target) {
        requireUnmanaged();
        StorageBackendSettings before = settings.current();
        StorageRoute routeBefore = storage.route();
        if (routeBefore.writeKind() == target) {
            return view();
        }
        if (target == StorageType.S3) {
            requireReachable(before.s3());
        }
        ObjectStorage targetStorage = owned(backends.backend(target, before.s3()));
        importExclusion.exclusively(() -> startMove(before, routeBefore, target, targetStorage));
        auditor.recordStaffAction(server, actorEmail, "Started moving uploaded files to " + describe(target) + " storage");
        return view();
    }

    private synchronized StorageTransfer startMove(StorageBackendSettings before, StorageRoute routeBefore, StorageType target,
                                           ObjectStorage targetStorage) {
        if (!settings.current().equals(before) || storage.route() != routeBefore) {
            throw changedMeanwhile();
        }
        requireSettledDatabase();
        if (imports.any()) {
            throw new ConflictException("Wait until the import finishes before moving the uploaded files", IMPORT_RUNNING_CODE);
        }
        if (oldCopyRemoval.isRemovingOldCopies()) {
            throw new ConflictException("Wait until the old copies are removed", REMOVING_OLD_COPIES_CODE);
        }
        boolean movingBack = before.migrationTarget().isPresent();
        if (!movingBack && !before.hasRoomForAnotherOldCopy()) {
            throw new ConflictException("Remove the old copies before moving the files again", OLD_COPIES_FULL_CODE);
        }
        StorageRoute.Migrating migrating = new StorageRoute.Migrating(routeBefore.writeKind(), routeBefore.writeTarget(), target,
            targetStorage, routeBefore.retiredCopies());
        StorageTransfer transfer = StorageTransfer.started(EntityIds.next(), routeBefore.writeKind(), target, clock.instant());
        unitOfWork.write(() -> {
            transfers.latest().filter(StorageTransfer::isRunning)
                .ifPresent(running -> transfers.save(running.progressed(running.counts(),
                    "Cancelled: the files are being moved back", clock.instant()).finished(StorageTransferState.FAILED, clock.instant())));
            settings.update(stored -> stored.migrating(routeBefore.writeKind(), target));
            transfers.insert(transfer);
            unitOfWork.afterCommit(() -> begin(migrating, transfer));
        });
        return transfer;
    }

    public StorageBackendView retry(Server server, String actorEmail) {
        requireUnmanaged();
        importExclusion.exclusively(this::restartStoppedTransfer);
        auditor.recordStaffAction(server, actorEmail, "Retried moving uploaded files to "
            + describe(settings.current().migrationTarget().orElse(settings.current().active())) + " storage");
        return view();
    }

    private synchronized StorageTransfer restartStoppedTransfer() {
        requireSettledDatabase();
        StorageTransfer stopped = transfers.latest()
            .filter(transfer -> transfer.state() == StorageTransferState.FAILED || (transfer.isRunning() && !jobAlive()))
            .filter(transfer -> settings.current().migrationTarget().isPresent())
            .orElseThrow(() -> new ConflictException("There is no failed file copy to retry", NO_FAILED_TRANSFER_CODE));
        if (!(storage.route() instanceof StorageRoute.Migrating migrating)) {
            throw new ConflictException("There is no failed file copy to retry", NO_FAILED_TRANSFER_CODE);
        }
        StorageTransfer restarted = stopped.restarted(clock.instant());
        transfers.save(restarted);
        begin(migrating, restarted);
        return restarted;
    }

    public StorageBackendView removeOldCopies(Server server, String actorEmail) {
        requireUnmanaged();
        importExclusion.exclusively(this::startOldCopyRemoval);
        auditor.recordStaffAction(server, actorEmail, "Started removing the old copies of the uploaded files");
        return view();
    }

    private synchronized StorageBackendSettings startOldCopyRemoval() {
        requireSettledDatabase();
        StorageBackendSettings current = settings.current();
        if (current.migrationTarget().isPresent()) {
            throw new ConflictException("Wait until the running file copy finishes", TRANSFER_RUNNING_CODE);
        }
        if (current.oldCopies().isEmpty()) {
            throw new ConflictException("There are no old copies to remove", NO_OLD_COPIES_CODE);
        }
        if (!oldCopyRemoval.start()) {
            throw new ConflictException("The old copies are already being removed", REMOVING_OLD_COPIES_CODE);
        }
        worker.execute(() -> removeEach(current));
        return current;
    }

    @EventListener(InstanceCommittedEvent.class)
    public void resumeAfterStart() {
        StorageRoute route = storage.route();
        if (route instanceof StorageRoute.Stable stable && !stable.fallbacks().isEmpty()) {
            worker.execute(() -> keepOnlyFallbacksWithFiles(stable));
            return;
        }
        if (!(route instanceof StorageRoute.Migrating migrating)) {
            return;
        }
        synchronized (this) {
            Optional<StorageTransfer> latest = transfers.latest();
            if (latest.isPresent() && latest.get().state() == StorageTransferState.FAILED) {
                notice.publish("Moving the uploaded files to {} storage stopped with errors. Open {} to retry.",
                    describe(migrating.targetKind()), PanelLocations.STORAGE);
                return;
            }
            StorageTransfer transfer = latest.filter(StorageTransfer::isRunning).orElseGet(() -> {
                StorageTransfer started = StorageTransfer.started(EntityIds.next(), migrating.sourceKind(), migrating.targetKind(),
                    clock.instant());
                transfers.insert(started);
                return started;
            });
            begin(migrating, transfer);
        }
    }

    @PreDestroy
    void stop() throws InterruptedException {
        stopping = true;
        wakeup.wakeAll();
        worker.shutdownNow();
        worker.awaitTermination(10, TimeUnit.SECONDS);
    }

    private void begin(StorageRoute.Migrating migrating, StorageTransfer transfer) {
        storage.use(migrating);
        long jobGeneration = generation.incrementAndGet();
        wakeup.wakeAll();
        currentJob = worker.submit(() -> runTransfer(migrating, transfer, jobGeneration));
    }

    private boolean jobAlive() {
        return currentJob != null && !currentJob.isDone();
    }

    private void runTransfer(StorageRoute.Migrating migrating, StorageTransfer transfer, long jobGeneration) {
        Optional<StorageTransfer> finished;
        try {
            finished = new StorageTransferJob(migrating, transfer, transfers, preSwitchUploads, clock,
                () -> stopping || generation.get() != jobGeneration, wakeup).run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (RuntimeException e) {
            log.error("Recording the file copy failed", e);
            notice.publish("Moving the uploaded files to {} storage stopped. Open {} to retry.",
                describe(migrating.targetKind()), PanelLocations.STORAGE);
            return;
        }
        finished.ifPresent(result -> settle(migrating, result));
    }

    private synchronized void settle(StorageRoute.Migrating migrating, StorageTransfer result) {
        if (storage.route() != migrating) {
            return;
        }
        if (result.state() != StorageTransferState.DONE) {
            notice.publish("Moving the uploaded files to {} storage stopped: {} file(s) could not be copied ({}). The files are"
                    + " still served from both places. Open {} to retry.", describe(migrating.targetKind()),
                result.counts().failedObjects(), result.lastError(), PanelLocations.STORAGE);
            return;
        }
        String previousBase = migrating.sourceKind() == StorageType.S3 ? migrating.source().publicBaseUrl() : null;
        StorageBackendSettings migrated = settings.update(stored -> stored.migrated(previousBase));
        StorageRoute.Stable stable = new StorageRoute.Stable(migrating.targetKind(), migrating.target(), List.of(),
            StorageRoutes.oldCopies(migrated, backends));
        storage.use(stable);
        storage.recognizeFormerUrls(StorageRoutes.formerUrlParsers(migrated));
        backends.retireUnused(stable);
        transfers.deleteFinishedBeyondNewest(KEPT_TRANSFERS);
        notice.publish("Moved the uploaded files to {} storage ({} copied, {} already there). The old copies were kept and"
                + " deletes still reach them; remove them in {} once you no longer need them.", describe(migrating.targetKind()),
            result.counts().copiedObjects(), result.counts().skippedObjects(), PanelLocations.STORAGE);
    }

    private void removeEach(StorageBackendSettings snapshot) {
        try {
            for (StorageBackendAddress address : snapshot.oldCopies()) {
                if (stopping) {
                    return;
                }
                OldCopyRemoval.Outcome outcome = oldCopyRemoval.remove(backends.backend(address));
                if (outcome.complete()) {
                    forgetOldCopy(address);
                    notice.publish("Removed the old copies in {} storage ({} file(s)).", describe(address.kind()), outcome.removed());
                } else {
                    notice.publish("Could not remove the old copies in {} storage: {}. Open {} to try again.",
                        describe(address.kind()), outcome.problem(), PanelLocations.STORAGE);
                }
            }
        } catch (RuntimeException e) {
            log.error("Removing the old copies failed", e);
            notice.publish("Removing the old copies of the uploaded files failed. Open {} to try again.", PanelLocations.STORAGE);
        } finally {
            oldCopyRemoval.finish();
        }
    }

    private synchronized void forgetOldCopy(StorageBackendAddress address) {
        StorageBackendSettings updated = settings.update(stored -> stored.withoutOldCopy(address));
        StorageRoute next = storage.route().withRetiredCopies(StorageRoutes.oldCopies(updated, backends));
        storage.use(next);
        backends.retireUnused(next);
    }

    private void keepOnlyFallbacksWithFiles(StorageRoute.Stable stable) {
        List<ObjectStorage> holding = stable.fallbacks().stream().filter(OldCopyRemoval::holdsFiles).toList();
        synchronized (this) {
            if (storage.route() == stable) {
                StorageRoute.Stable kept = stable.withFallbacks(holding);
                storage.use(kept);
                backends.retireUnused(kept);
            }
        }
        if (!holding.isEmpty()) {
            notice.publish("MODL_STORAGE selects {} storage, but files uploaded earlier are still in the storage chosen in the"
                + " panel. They stay readable from there; to move them, remove MODL_STORAGE, move the files in {}, then set it"
                + " again.", describe(stable.kind()), PanelLocations.STORAGE);
        }
    }

    private void requireReachable(StorageProperties.S3 connection) {
        S3ConnectionResult tested = tester.test(connection);
        if (!tested.ok()) {
            throw new ValidationException(tested.message(), S3_NOT_READY_CODE);
        }
    }

    private ObjectStorage owned(ObjectStorage targetStorage) {
        Verdict owner = ownerMarker.claim(targetStorage);
        if (!owner.isOwned()) {
            throw new ConflictException("The new storage cannot be used: " + owner.requireRefusalReason(), NOT_OWNED_CODE);
        }
        return targetStorage;
    }

    private StorageProperties.S3 withStoredSecret(StorageProperties.S3 requested) {
        if (requested.secretKey() != null) {
            return requested;
        }
        StorageProperties.S3 stored = settings.current().s3();
        return requested.sameAccountAt(stored) ? requested.withSecretKey(stored.secretKey()) : requested;
    }

    private static StorageProperties.S3 validated(StorageProperties.S3 requested) {
        S3Addresses.requireValid(requested);
        return requested;
    }

    private void requireUnmanaged() {
        if (isManaged()) {
            throw new ConflictException(MANAGED_MESSAGE, MANAGED_CODE);
        }
    }

    private static ConflictException changedMeanwhile() {
        return new ConflictException("The storage settings changed meanwhile; reload and try again", CHANGED_CODE);
    }

    private static String describe(StorageType type) {
        return type == StorageType.S3 ? "S3" : "local";
    }

    private static Thread workerThread(Runnable task) {
        Thread thread = new Thread(task, "modl-storage-transfer");
        thread.setDaemon(true);
        return thread;
    }
}
