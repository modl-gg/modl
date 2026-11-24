package gg.modl.backend.replay.service;

import static gg.modl.backend.database.jooq.Tables.REPLAYS;

import gg.modl.backend.content.CappedText;
import gg.modl.backend.database.StorableText;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.util.ByteFormatUtil;
import gg.modl.backend.infrastructure.validation.BeanValidationRunner;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.replay.data.ReplayLabel;
import gg.modl.backend.replay.dto.InitReplayUploadResponse;
import gg.modl.backend.replay.dto.PublicReplayResponse;
import gg.modl.backend.replay.repository.ReplayRepository;
import gg.modl.backend.replay.util.ReplayReferenceUtil;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.MediaCategory;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.StorageFolders;
import gg.modl.backend.storage.StorageLimitPolicy;
import gg.modl.backend.storage.StoredObject;
import gg.modl.backend.storage.UploadTarget;
import gg.modl.backend.storage.service.StorageKeyUtils;
import gg.modl.backend.storage.service.StorageDeletionService;
import gg.modl.backend.storage.service.StorageMetadataService;
import gg.modl.backend.storage.service.UploadConfirmation;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReplayService {
    private static final String REPLAY_FILE_NAME = "replay.modlreplay";
    private static final String REPLAY_CONTENT_TYPE = "application/octet-stream";

    private final ReplayRepository replayRepository;
    private final ObjectStorage objectStorage;
    private final StorageMetadataService storageMetadataService;
    private final StorageDeletionService storageDeletionService;
    private final StorageLimitPolicy storageLimitPolicy;
    private final BeanValidationRunner validationRunner;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public InitReplayUploadResponse initUpload(
        Server server,
        String mcVersion,
        long fileSize,
        @Nullable String targetUuid,
        @Nullable String targetName
    ) {
        long maxFileSize = storageLimitPolicy.maxUploadBytes(MediaCategory.REPLAY);
        if (fileSize <= 0 || fileSize > maxFileSize) {
            throw new ValidationException("Replay size must be between 1 byte and " + ByteFormatUtil.formatCompact(maxFileSize));
        }

        String normalizedTargetUuid = ReplayReferenceUtil.validUuidIfPresent(targetUuid);
        String normalizedTargetName = normalizeTargetName(targetName);
        String storableMcVersion = storableMcVersion(mcVersion);

        String replayId = UUID.randomUUID().toString();
        String storageKey = StorageKeyUtils.buildKey(server, StorageFolders.REPLAYS, REPLAY_FILE_NAME, null);
        storageLimitPolicy.reserve(storageKey, fileSize, storageLimitPolicy.uploadDeadline());
        UploadTarget target = presignReserved(storageKey, fileSize);

        ReplayDocument doc = new ReplayDocument();
        doc.setId(replayId);
        doc.setMcVersion(storableMcVersion);
        doc.setTargetUuid(normalizedTargetUuid);
        doc.setTargetName(normalizedTargetName);
        doc.setFileSize(fileSize);
        doc.setStorageKey(storageKey);
        doc.setStatus(ReplayDocument.STATUS_PENDING);
        doc.setCreatedAt(clock.instant());
        unitOfWork.write(() -> replayRepository.insert(doc));

        return new InitReplayUploadResponse(replayId, target.url(), target.method(), target.headers());
    }

    public boolean confirmUpload(Server server, String replayId) {
        Optional<ReplayDocument> existing = replayRepository.findById(replayId);
        if (existing.isEmpty()) {
            return false;
        }

        ReplayDocument doc = existing.get();
        String storageKey = doc.getStorageKey();
        Optional<StoredObject> stored = storageKey == null ? Optional.empty() : objectStorage.stat(storageKey);
        if (storageKey == null || stored.isEmpty()) {
            markFailed(doc);
            log.warn("Replay {} upload not found in storage", replayId);
            return false;
        }

        long storedSize = stored.get().size();
        UploadConfirmation confirmation = unitOfWork.write(() -> recordUpload(server, doc, storageKey, storedSize));
        if (!confirmation.recorded()) {
            storageDeletionService.discard(server, storageKey);
            markFailed(doc);
            log.warn("Replay {} was confirmed after its upload reservation lapsed", replayId);
            return false;
        }
        log.debug("Replay {} confirmed", replayId);
        return true;
    }

    private UploadTarget presignReserved(String storageKey, long fileSize) {
        try {
            return objectStorage.presignUpload(storageKey, REPLAY_CONTENT_TYPE, fileSize);
        } catch (RuntimeException e) {
            storageLimitPolicy.cancel(storageKey);
            throw e;
        }
    }

    private UploadConfirmation recordUpload(Server server, ReplayDocument doc, String storageKey, long storedSize) {
        UploadConfirmation confirmation =
            storageMetadataService.confirmUpload(server, storageKey, storedSize, REPLAY_CONTENT_TYPE);
        if (confirmation.recorded()) {
            replayRepository.updateUpload(doc.getId(), ReplayDocument.STATUS_COMPLETE, storedSize);
        }
        return confirmation;
    }

    private void markFailed(ReplayDocument doc) {
        unitOfWork.write(() -> {
            replayRepository.updateUpload(doc.getId(), ReplayDocument.STATUS_FAILED, doc.getFileSize());
        });
    }

    public enum SubmitLabelsResult { OK, NOT_FOUND }

    public SubmitLabelsResult submitLabels(Server server, String replayId, List<ReplayLabel> labels) {
        validateLabels(labels);

        SubmitLabelsResult result = unitOfWork.write(() -> saveLabels(replayId, labels));
        if (result == SubmitLabelsResult.OK) {
            log.debug("Saved {} labels for replay {}", labels.size(), replayId);
        }
        return result;
    }

    private SubmitLabelsResult saveLabels(String replayId, List<ReplayLabel> labels) {
        return replayRepository.findById(replayId)
            .filter(replay -> ReplayDocument.STATUS_COMPLETE.equals(replay.getStatus()))
            .flatMap(replay -> replayRepository.replaceLabels(replayId, labels))
            .map(replay -> SubmitLabelsResult.OK)
            .orElse(SubmitLabelsResult.NOT_FOUND);
    }

    private void validateLabels(List<ReplayLabel> labels) {
        if (labels.size() > RequestValidationLimits.REPLAY_LITE_LABELS_MAX_ENTRIES) {
            throw new ValidationException("A replay can have at most " + RequestValidationLimits.REPLAY_LITE_LABELS_MAX_ENTRIES
                + " labelled players", "INVALID_ARGUMENT");
        }
        for (ReplayLabel label : labels) {
            validationRunner.validate(label);
        }
    }

    public Optional<PublicReplayResponse> getPublicReplay(Server server, String replayId) {
        return replayRepository.findById(replayId)
            .flatMap(doc -> doc.completedStorageKey().map(storageKey -> new PublicReplayResponse(
                doc.getId(),
                doc.getMcVersion(),
                doc.getFileSize(),
                doc.getCreatedAt().toEpochMilli(),
                objectStorage.publicUrl(storageKey),
                doc.getStatus(),
                doc.hasLabels()
            )));
    }

    private static String storableMcVersion(String value) {
        return CappedText.of(StorableText.strip(value), TextLimits.limit(REPLAYS.MC_VERSION));
    }

    private @Nullable String normalizeTargetName(@Nullable String value) {
        String normalized = ReplayReferenceUtil.normalize(value);
        if (normalized == null) {
            return null;
        }
        if (normalized.length() > RequestValidationLimits.LOG_USERNAME_MAX_LENGTH) {
            throw new ValidationException("Target name is too long");
        }
        return normalized;
    }
}
