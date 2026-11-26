package gg.modl.backend.replay.service;

import static gg.modl.backend.database.jooq.Tables.REPLAYS;

import gg.modl.backend.content.CappedText;
import gg.modl.backend.database.StorableText;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.util.ByteFormatUtil;
import gg.modl.backend.infrastructure.validation.BeanValidationRunner;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.replay.data.ReplayLabel;
import gg.modl.backend.replay.dto.InitReplayUploadResponse;
import gg.modl.backend.replay.dto.PanelReplayView;
import gg.modl.backend.replay.repository.ReplayRepository;
import gg.modl.backend.replay.util.ReplayReferenceUtil;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.MediaCategory;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.StorageFolders;
import gg.modl.backend.storage.StorageLimitPolicy;
import gg.modl.backend.storage.UploadTarget;
import gg.modl.backend.storage.service.StorageKeyUtils;
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

    private UploadTarget presignReserved(String storageKey, long fileSize) {
        try {
            return objectStorage.presignUpload(storageKey, REPLAY_CONTENT_TYPE, fileSize);
        } catch (RuntimeException e) {
            storageLimitPolicy.cancel(storageKey);
            throw e;
        }
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

    public Optional<PanelReplayView> getPanelReplay(String replayId) {
        if (!TextLimits.canMatch(REPLAYS.ID, replayId)) return Optional.empty();
        return replayRepository.findById(replayId)
            .filter(doc -> doc.completedStorageKey().isPresent())
            .map(doc -> new PanelReplayView(
                doc.getId(),
                doc.getMcVersion(),
                doc.getStatus(),
                doc.getFileSize(),
                doc.getCreatedAt().toEpochMilli(),
                doc.getTargetUuid(),
                doc.getTargetName(),
                doc.hasLabels(),
                panelFileUrl(doc.getId()),
                doc.getFormatVersion()
            ));
    }

    public Optional<ReplayFile> completedFile(String replayId) {
        if (!TextLimits.canMatch(REPLAYS.ID, replayId)) return Optional.empty();
        return replayRepository.findById(replayId)
            .flatMap(ReplayDocument::completedStorageKey)
            .flatMap(objectStorage::stat)
            .map(object -> new ReplayFile(objectStorage, object));
    }

    public static String panelFileUrl(String replayId) {
        return RESTMappingV1.PANEL_REPLAYS + "/" + replayId + "/file";
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
