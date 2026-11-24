package gg.modl.backend.storage.service;

import gg.modl.backend.infrastructure.scheduling.ScheduledDatabaseJobs;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.ConfirmedStorageTotal;
import gg.modl.backend.storage.ListedObject;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.StorageNamespace;
import gg.modl.backend.storage.repository.StorageFile;
import gg.modl.backend.storage.repository.StorageFileRepository;
import gg.modl.backend.storage.repository.StorageReservationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class StorageReclamationService {
    static final int BATCH_SIZE = 500;
    static final int RATIO_GUARD_MIN_ORPHANS = 10;

    private final ObjectStorage objectStorage;
    private final StorageFileRepository storageFileRepository;
    private final StorageReservationRepository reservationRepository;
    private final StorageDeletionService storageDeletionService;
    private final StorageOwnership storageOwnership;
    private final ConfirmedStorageTotal confirmedStorageTotal;
    private final InstanceContext instanceContext;
    private final ScheduledDatabaseJobs databaseJobs;
    private final Clock clock;
    private final Duration orphanTtl;
    private final AtomicReference<@Nullable String> lastRefusal = new AtomicReference<>();

    public StorageReclamationService(
        ObjectStorage objectStorage,
        StorageFileRepository storageFileRepository,
        StorageReservationRepository reservationRepository,
        StorageDeletionService storageDeletionService,
        StorageOwnership storageOwnership,
        ConfirmedStorageTotal confirmedStorageTotal,
        InstanceContext instanceContext,
        ScheduledDatabaseJobs databaseJobs,
        Clock clock,
        @Value("${modl.storage.reclamation.orphan-ttl:PT6H}") Duration orphanTtl
    ) {
        this.objectStorage = objectStorage;
        this.storageFileRepository = storageFileRepository;
        this.reservationRepository = reservationRepository;
        this.storageDeletionService = storageDeletionService;
        this.storageOwnership = storageOwnership;
        this.confirmedStorageTotal = confirmedStorageTotal;
        this.instanceContext = instanceContext;
        this.databaseJobs = databaseJobs;
        this.clock = clock;
        this.orphanTtl = orphanTtl;
    }

    @Scheduled(
        fixedDelayString = "${modl.storage.reclamation.settle-interval:PT10M}",
        initialDelayString = "${modl.storage.reclamation.initial-delay:PT2M}"
    )
    public void settleLapsedReservations() {
        databaseJobs.run("Settling lapsed storage reservations", () -> settleLapsedReservations(instanceContext.instance()));
    }

    @Scheduled(cron = "${modl.storage.reclamation.sweep-cron:0 30 3 * * *}")
    public void sweepUnrecordedObjects() {
        databaseJobs.run("Reclaiming unrecorded storage objects", () -> {
            sweepUnrecordedObjects(instanceContext.instance());
            confirmedStorageTotal.resync();
        });
    }

    int settleLapsedReservations(Server server) {
        Instant now = clock.instant();
        int abandoned = 0;
        List<String> lapsed;
        int examined = 0;
        do {
            lapsed = reservationRepository.findSettleableKeys(now, BATCH_SIZE);
            int retained = 0;
            for (String key : lapsed) {
                StorageDeletionService.SettleOutcome outcome = storageDeletionService.settle(server, key);
                if (outcome == StorageDeletionService.SettleOutcome.ABANDONED) {
                    abandoned++;
                } else if (outcome == StorageDeletionService.SettleOutcome.RETAINED) {
                    retained++;
                }
            }
            examined += lapsed.size();
            if (retained == lapsed.size()) {
                break;
            }
        } while (lapsed.size() == BATCH_SIZE);
        if (examined > 0) {
            log.debug("Settled {} lapsed storage reservation(s), {} abandoned upload(s) removed", examined, abandoned);
        }
        return abandoned;
    }

    int sweepUnrecordedObjects(Server server) {
        StorageOwnerMarker.Verdict verdict = storageOwnership.verify();
        if (!verdict.isOwned()) {
            refuse("Storage reclamation is disabled: " + verdict.requireRefusalReason());
            return 0;
        }
        SweepTally tally = new SweepTally();
        Instant cutoff = clock.instant().minus(orphanTtl);
        boolean recordsExist = storageFileRepository.hasAnyFile();
        try (Stream<ListedObject> objects = objectStorage.list(StorageNamespace.KEY_PREFIX)) {
            Iterator<ListedObject> listed = objects.iterator();
            List<String> batch = new ArrayList<>(BATCH_SIZE);
            while (listed.hasNext()) {
                ListedObject object = listed.next();
                if (!recordsExist) {
                    refuse("Storage reclamation is disabled: the database has no file records while the storage holds objects;"
                        + " check that this installation uses the right database");
                    return 0;
                }
                tally.examined++;
                if (object.lastModified().isBefore(cutoff)) {
                    batch.add(object.key());
                }
                if (batch.size() == BATCH_SIZE && !reclaimBatch(server, batch, tally)) {
                    return tally.reclaimed;
                }
            }
            if (!reclaimBatch(server, batch, tally)) {
                return tally.reclaimed;
            }
        }
        lastRefusal.set(null);
        if (tally.reclaimed > 0) {
            log.info("Reclaimed {} unrecorded storage object(s)", tally.reclaimed);
        }
        return tally.reclaimed;
    }

    private boolean reclaimBatch(Server server, List<String> candidates, SweepTally tally) {
        if (candidates.isEmpty()) {
            return true;
        }
        Set<String> reserved = reservationRepository.findReservedKeys(candidates);
        Set<String> recorded = storageFileRepository.findByKeys(candidates).stream()
            .map(StorageFile::key)
            .collect(Collectors.toSet());
        List<String> orphans = candidates.stream()
            .filter(key -> !recorded.contains(key) && !reserved.contains(key))
            .toList();
        candidates.clear();
        tally.orphans += orphans.size();
        if (tally.orphans >= RATIO_GUARD_MIN_ORPHANS && tally.orphans * 2L > tally.examined) {
            refuse("Storage reclamation stopped: " + tally.orphans + " of " + tally.examined
                + " examined objects have no file record, which suggests the wrong database or a shared storage location");
            return false;
        }
        if (orphans.isEmpty()) {
            return true;
        }
        Set<String> undeleted = objectStorage.deleteAll(orphans);
        List<String> deleted = orphans.stream().filter(key -> !undeleted.contains(key)).toList();
        if (!deleted.isEmpty()) {
            storageDeletionService.notifyDeleted(server, deleted);
        }
        tally.reclaimed += deleted.size();
        return true;
    }

    private void refuse(String reason) {
        if (!reason.equals(lastRefusal.getAndSet(reason))) {
            log.warn(reason);
        }
    }

    private static final class SweepTally {
        private long examined;
        private long orphans;
        private int reclaimed;
    }
}
