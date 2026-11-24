package gg.modl.backend.storage.service;

import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.PublicUpload;
import gg.modl.backend.storage.StorageLimitExceededException;
import gg.modl.backend.storage.StorageLimitPolicy;
import gg.modl.backend.storage.StoredObject;
import gg.modl.backend.storage.TemporaryUploadLimitExceededException;
import gg.modl.backend.storage.UploadTarget;
import gg.modl.backend.storage.dto.response.PresignUploadResponse;
import gg.modl.backend.storage.dto.response.UploadResponse;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UploadOrchestrationService {
    private final MediaValidationService validationService;
    private final ObjectStorage objectStorage;
    private final StorageMetadataService storageMetadataService;
    private final StorageLimitPolicy storageLimitPolicy;

    public PresignOutcome presign(Server server, UploadPresignRequest request) {
        Optional<String> rejection = validationService.rejectionOf(
            request.fileName(),
            request.contentType(),
            request.fileSize(),
            request.uploadType()
        );
        if (rejection.isPresent()) {
            return PresignOutcome.failed(PresignStatus.VALIDATION_FAILED, rejection.get());
        }

        String key = StorageKeyUtils.buildKey(server, request.uploadType(), request.fileName(), request.entityId());
        Instant uploadDeadline = storageLimitPolicy.uploadDeadline();
        try {
            String publicClientNetwork = request.publicClientNetwork();
            if (publicClientNetwork != null) {
                storageLimitPolicy.reservePublic(key, request.fileSize(), uploadDeadline,
                    publicUpload(request.entityId(), publicClientNetwork, key));
            } else {
                storageLimitPolicy.reserve(key, request.fileSize(), uploadDeadline);
            }
        } catch (TemporaryUploadLimitExceededException e) {
            return PresignOutcome.failed(PresignStatus.TEMP_LIMIT_EXCEEDED, e.getMessage());
        } catch (StorageLimitExceededException e) {
            return PresignOutcome.failed(PresignStatus.STORAGE_FULL, e.getMessage());
        }
        UploadTarget target = presignReserved(key, request.contentType(), request.fileSize());
        return PresignOutcome.success(new PresignUploadResponse(target.url(), key, target.expiresAt(), target.method(), target.headers()));
    }

    private UploadTarget presignReserved(String key, String contentType, long fileSize) {
        try {
            return objectStorage.presignUpload(key, contentType, fileSize);
        } catch (RuntimeException e) {
            storageLimitPolicy.cancel(key);
            throw e;
        }
    }

    public ConfirmOutcome confirm(Server server, String key) {
        validationService.assertKeyOwnedByServer(server, key);

        Optional<StoredObject> stored = objectStorage.stat(key);
        if (stored.isEmpty()) {
            return ConfirmOutcome.status(ConfirmStatus.UPLOAD_NOT_FOUND);
        }
        StoredObject object = stored.get();

        UploadConfirmation confirmation = storageMetadataService.confirmUpload(server, key, object.size(), object.contentType());
        if (!confirmation.recorded()) {
            return ConfirmOutcome.status(ConfirmStatus.UPLOAD_NOT_FOUND);
        }
        return ConfirmOutcome.success(new UploadResponse(
            key,
            objectStorage.publicUrl(key),
            StorageKeyUtils.extractFileName(key),
            object.size(),
            object.contentType()
        ));
    }

    public record UploadPresignRequest(
        String uploadType,
        String fileName,
        String contentType,
        long fileSize,
        @Nullable String entityId,
        @Nullable String publicClientNetwork
    ) {}

    private static PublicUpload publicUpload(@Nullable String entityId, String publicClientNetwork, String key) {
        return TempUploadKeys.isAnonymousTempEntity(entityId)
            ? PublicUpload.unattached(publicClientNetwork)
            : PublicUpload.forRecord(publicClientNetwork, StorageKeyUtils.parentPrefix(key));
    }

    public enum PresignStatus {
        SUCCESS,
        VALIDATION_FAILED,
        STORAGE_FULL,
        TEMP_LIMIT_EXCEEDED
    }

    public record PresignOutcome(PresignStatus status, @Nullable String message, @Nullable PresignUploadResponse upload) {
        static PresignOutcome success(PresignUploadResponse upload) {
            return new PresignOutcome(PresignStatus.SUCCESS, null, upload);
        }

        static PresignOutcome failed(PresignStatus status, String message) {
            return new PresignOutcome(status, message, null);
        }

        public PresignUploadResponse requireUpload() {
            if (upload == null) {
                throw new IllegalStateException("Only a successful presign carries an upload target, this one is " + status);
            }
            return upload;
        }

        public String requireMessage() {
            if (message == null) {
                throw new IllegalStateException("Only a refused presign carries a message, this one is " + status);
            }
            return message;
        }
    }

    public enum ConfirmStatus {
        SUCCESS,
        UPLOAD_NOT_FOUND
    }

    public record ConfirmOutcome(ConfirmStatus status, @Nullable UploadResponse upload) {
        public static ConfirmOutcome success(UploadResponse upload) {
            return new ConfirmOutcome(ConfirmStatus.SUCCESS, upload);
        }

        public static ConfirmOutcome status(ConfirmStatus status) {
            return new ConfirmOutcome(status, null);
        }

        public UploadResponse requireUpload() {
            if (upload == null) {
                throw new IllegalStateException("Only a successful confirmation carries the upload, this one is " + status);
            }
            return upload;
        }
    }
}
