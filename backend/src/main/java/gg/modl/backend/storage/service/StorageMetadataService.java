package gg.modl.backend.storage.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.ConfirmedStorageTotal;
import gg.modl.backend.storage.MediaCategory;
import gg.modl.backend.storage.repository.StorageFile;
import gg.modl.backend.storage.repository.StorageFileRepository;
import gg.modl.backend.storage.repository.StorageReservation;
import gg.modl.backend.storage.repository.StorageReservationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class StorageMetadataService {
    private final StorageFileRepository storageFileRepository;
    private final StorageReservationRepository reservationRepository;
    private final ConfirmedStorageTotal confirmedStorageTotal;
    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private final Duration unattachedUploadTtl;

    public StorageMetadataService(
        StorageFileRepository storageFileRepository,
        StorageReservationRepository reservationRepository,
        ConfirmedStorageTotal confirmedStorageTotal,
        UnitOfWork unitOfWork,
        Clock clock,
        @Value("${modl.storage.unattached-upload-ttl:PT24H}") Duration unattachedUploadTtl
    ) {
        this.storageFileRepository = storageFileRepository;
        this.reservationRepository = reservationRepository;
        this.confirmedStorageTotal = confirmedStorageTotal;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
        this.unattachedUploadTtl = unattachedUploadTtl;
    }

    public Map<String, StorageFile> findConfirmedFiles(Server server, Collection<String> keys) {
        return storageFileRepository.findByKeys(keys).stream()
            .collect(Collectors.toMap(StorageFile::key, Function.identity()));
    }

    public Set<String> existingKeys(Server server, Collection<String> keys) {
        return storageFileRepository.findByKeys(keys).stream()
            .map(StorageFile::key)
            .collect(Collectors.toSet());
    }

    public UploadConfirmation confirmUpload(Server server, String key, long size, String contentType) {
        return unitOfWork.write(() -> claimAndRecord(key, size, contentType));
    }

    private UploadConfirmation claimAndRecord(String key, long size, String contentType) {
        Optional<StorageReservation> reservation = reservationRepository.claim(key);
        if (reservation.isEmpty()) {
            return storageFileRepository.findByKey(key).isPresent() ? UploadConfirmation.ALREADY_RECORDED : UploadConfirmation.EXPIRED;
        }
        Instant now = clock.instant();
        StorageFile file = new StorageFile(
            key, StorageKeyUtils.extractFileName(key), size, contentType, StorageKeyUtils.categorizeFile(key), now);
        if (!storageFileRepository.insertIfAbsent(file, reservation.get().origin(), unattachedExpiry(key, now))) {
            return UploadConfirmation.ALREADY_RECORDED;
        }
        unitOfWork.afterCommit(() -> confirmedStorageTotal.added(size));
        return UploadConfirmation.RECORDED;
    }

    @Nullable
    private Instant unattachedExpiry(String key, Instant confirmedAt) {
        return StorageKeyUtils.mediaCategoryOf(key)
            .filter(MediaCategory::attachedOnSubmission)
            .map(category -> confirmedAt.plus(unattachedUploadTtl))
            .orElse(null);
    }
}
