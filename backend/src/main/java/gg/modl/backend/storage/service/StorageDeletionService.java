package gg.modl.backend.storage.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.ConfirmedStorageTotal;
import gg.modl.backend.storage.MediaCategory;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.repository.StorageFile;
import gg.modl.backend.storage.repository.StorageFileRepository;
import gg.modl.backend.storage.repository.StorageReservation;
import gg.modl.backend.storage.repository.StorageReservationRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class StorageDeletionService {
    private final StorageFileRepository storageFileRepository;
    private final StorageReservationRepository reservationRepository;
    private final ObjectStorage objectStorage;
    private final ConfirmedStorageTotal confirmedStorageTotal;
    private final UnitOfWork unitOfWork;
    private final List<StoredObjectDeletionListener> deletionListeners;

    public StorageDeletion delete(Server server, Collection<String> keys) {
        if (keys.isEmpty()) {
            return StorageDeletion.NOTHING;
        }
        boolean recorded = unitOfWork.write(() -> removeRecords(keys));
        boolean existed = recorded || keys.stream().anyMatch(objectStorage::exists);
        return new StorageDeletion(existed, deleteObjects(server, keys));
    }

    public MediaDeletion deleteRecordedMedia(Server server, String key, MediaCategory category) {
        if (StorageKeyUtils.mediaCategoryOf(key).filter(category::equals).isEmpty()) {
            return MediaDeletion.WRONG_CATEGORY;
        }
        boolean recorded = unitOfWork.write(() -> storageFileRepository.findByKey(key)
            .filter(file -> file.key().equals(key))
            .map(file -> removeRecords(List.of(file.key())))
            .orElse(false));
        if (!recorded) {
            return MediaDeletion.NOT_FOUND;
        }
        deleteObjects(server, List.of(key));
        return MediaDeletion.DELETED;
    }

    int deleteUnattachedExpired(Server server, Instant now, int limit) {
        List<String> expired = unitOfWork.write(() -> {
            List<String> keys = storageFileRepository.findUnattachedExpired(now, limit);
            if (!keys.isEmpty()) {
                removeRecords(keys);
            }
            return keys;
        });
        if (!expired.isEmpty()) {
            deleteObjects(server, expired);
        }
        return expired.size();
    }

    public boolean discard(Server server, String key) {
        if (!objectStorage.delete(key)) {
            log.warn("Failed to delete discarded upload key={}", key);
            return false;
        }
        reservationRepository.release(List.of(key));
        return true;
    }

    public SettleOutcome settle(Server server, String key) {
        Optional<StorageReservation> abandoned = unitOfWork.write(() -> reservationRepository.claim(key)
            .filter(claimed -> storageFileRepository.findByKey(key).isEmpty()));
        if (abandoned.isEmpty()) {
            return SettleOutcome.CONFIRMED;
        }
        if (!objectStorage.delete(key)) {
            reservationRepository.reserve(abandoned.get());
            return SettleOutcome.RETAINED;
        }
        notifyDeleted(server, List.of(key));
        return SettleOutcome.ABANDONED;
    }

    private boolean removeRecords(Collection<String> keys) {
        List<StorageFile> files = storageFileRepository.findByKeys(keys);
        storageFileRepository.deleteByKeys(keys);
        long releasedReservations = reservationRepository.release(keys);
        long freedBytes = files.stream().mapToLong(StorageFile::size).sum();
        unitOfWork.afterCommit(() -> confirmedStorageTotal.removed(freedBytes));
        return !files.isEmpty() || releasedReservations > 0;
    }

    private Set<String> deleteObjects(Server server, Collection<String> keys) {
        Set<String> undeleted = objectStorage.deleteAll(keys);
        if (!undeleted.isEmpty()) {
            log.warn("{} stored object(s) could not be deleted and will be reclaimed later", undeleted.size());
        }
        notifyDeleted(server, keys);
        return undeleted;
    }

    void notifyDeleted(Server server, Collection<String> keys) {
        for (StoredObjectDeletionListener listener : deletionListeners) {
            try {
                listener.onStoredObjectsDeleted(server, keys);
            } catch (RuntimeException e) {
                log.warn("A storage deletion listener failed for {} key(s)", keys.size(), e);
            }
        }
    }

    public enum SettleOutcome {
        CONFIRMED,
        ABANDONED,
        RETAINED
    }
}
