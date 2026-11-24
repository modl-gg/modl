package gg.modl.backend.storage.settings;

import static gg.modl.backend.infrastructure.proto.ProtoMapperSupport.toTimestamp;

import gg.modl.backend.limits.EffectiveLimit;
import gg.modl.backend.limits.controller.OperatorLimitProtoMapper;
import gg.modl.backend.storage.StorageProperties;
import gg.modl.backend.storage.StorageType;
import gg.modl.backend.storage.backend.StorageBackendView;
import gg.modl.backend.storage.backend.StorageTransfer;
import gg.modl.backend.storage.s3.S3ConnectionResult;
import gg.modl.proto.modl.v1.S3ConnectionInput;
import gg.modl.proto.modl.v1.S3ConnectionView;
import gg.modl.proto.modl.v1.StorageBackendKind;
import gg.modl.proto.modl.v1.StorageSettingsResponse;
import gg.modl.proto.modl.v1.StorageTransferState;
import gg.modl.proto.modl.v1.StorageTransferView;
import gg.modl.proto.modl.v1.StorageUsageGroupKind;
import gg.modl.proto.modl.v1.StorageUsageGroupView;
import gg.modl.proto.modl.v1.TestS3ConnectionResponse;
import org.jspecify.annotations.Nullable;
import java.util.List;
import java.util.Optional;

final class StorageSettingsProtoMapper {
    private StorageSettingsProtoMapper() {
    }

    static StorageSettingsResponse toResponse(List<EffectiveLimit> limits, long limitsVersion, StorageBackendView backend,
                                              StorageUsage usage) {
        StorageSettingsResponse.Builder response = StorageSettingsResponse.newBuilder()
            .setLimitsVersion(limitsVersion)
            .setBackend(toBackend(backend))
            .setUsedBytes(usage.usedBytes())
            .setCapacityBytes(usage.capacityBytes());
        usage.groups().forEach(group -> response.addUsage(StorageUsageGroupView.newBuilder()
            .setGroup(toUsageGroup(group.group()))
            .setFiles(group.files())
            .setBytes(group.bytes())));
        response.addAllLimits(OperatorLimitProtoMapper.toLimits(limits));
        backend.latestTransfer().ifPresent(transfer -> response.setTransfer(toTransfer(transfer)));
        return response.build();
    }

    private static StorageUsageGroupKind toUsageGroup(StorageUsageGroup group) {
        return switch (group) {
            case REPLAYS -> StorageUsageGroupKind.STORAGE_USAGE_GROUP_KIND_REPLAYS;
            case EVIDENCE -> StorageUsageGroupKind.STORAGE_USAGE_GROUP_KIND_EVIDENCE;
            case TICKETS -> StorageUsageGroupKind.STORAGE_USAGE_GROUP_KIND_TICKETS;
            case ARTICLES -> StorageUsageGroupKind.STORAGE_USAGE_GROUP_KIND_ARTICLES;
            case ICONS -> StorageUsageGroupKind.STORAGE_USAGE_GROUP_KIND_ICONS;
            case OTHER -> StorageUsageGroupKind.STORAGE_USAGE_GROUP_KIND_OTHER;
        };
    }

    static StorageProperties.S3 toS3(S3ConnectionInput input) {
        return new StorageProperties.S3(input.getEndpoint(), input.getRegion(), input.getBucket(), input.getAccessKey(),
            input.hasSecretKey() ? input.getSecretKey() : null, input.getPublicUrl());
    }

    static Optional<StorageType> toStorageType(StorageBackendKind kind) {
        return switch (kind) {
            case STORAGE_BACKEND_KIND_LOCAL -> Optional.of(StorageType.LOCAL);
            case STORAGE_BACKEND_KIND_S3 -> Optional.of(StorageType.S3);
            case STORAGE_BACKEND_KIND_UNSPECIFIED, UNRECOGNIZED -> Optional.empty();
        };
    }

    static TestS3ConnectionResponse toResponse(S3ConnectionResult result) {
        return TestS3ConnectionResponse.newBuilder()
            .setOk(result.ok())
            .setCode(result.code())
            .setMessage(result.message())
            .build();
    }

    private static gg.modl.proto.modl.v1.StorageBackendView toBackend(StorageBackendView backend) {
        StorageProperties.S3 s3 = backend.s3();
        return gg.modl.proto.modl.v1.StorageBackendView.newBuilder()
            .setActive(toKind(backend.active()))
            .setManaged(backend.managed())
            .setS3Configured(s3.complete())
            .addAllOldCopies(backend.oldCopies().stream().map(StorageSettingsProtoMapper::toKind).toList())
            .setRemovingOldCopies(backend.removingOldCopies())
            .setS3(S3ConnectionView.newBuilder()
                .setEndpoint(orEmpty(s3.endpoint()))
                .setRegion(orEmpty(s3.region()))
                .setBucket(orEmpty(s3.bucket()))
                .setAccessKey(orEmpty(s3.accessKey()))
                .setHasSecretKey(s3.secretKey() != null)
                .setPublicUrl(orEmpty(s3.publicUrl())))
            .build();
    }

    private static StorageTransferView toTransfer(StorageTransfer transfer) {
        StorageTransferView.Builder view = StorageTransferView.newBuilder()
            .setId(transfer.id())
            .setState(toState(transfer.state()))
            .setSource(toKind(transfer.source()))
            .setTarget(toKind(transfer.target()))
            .setCopiedObjects(transfer.counts().copiedObjects())
            .setCopiedBytes(transfer.counts().copiedBytes())
            .setSkippedObjects(transfer.counts().skippedObjects())
            .setFailedObjects(transfer.counts().failedObjects())
            .setLastError(orEmpty(transfer.lastError()))
            .setStartedAt(toTimestamp(transfer.startedAt()))
            .setUpdatedAt(toTimestamp(transfer.updatedAt()));
        if (transfer.finishedAt() != null) {
            view.setFinishedAt(toTimestamp(transfer.finishedAt()));
        }
        return view.build();
    }

    private static StorageTransferState toState(gg.modl.backend.storage.backend.StorageTransferState state) {
        return switch (state) {
            case RUNNING -> StorageTransferState.STORAGE_TRANSFER_STATE_RUNNING;
            case FAILED -> StorageTransferState.STORAGE_TRANSFER_STATE_FAILED;
            case DONE -> StorageTransferState.STORAGE_TRANSFER_STATE_DONE;
        };
    }

    private static StorageBackendKind toKind(StorageType type) {
        return switch (type) {
            case LOCAL -> StorageBackendKind.STORAGE_BACKEND_KIND_LOCAL;
            case S3 -> StorageBackendKind.STORAGE_BACKEND_KIND_S3;
        };
    }

    private static String orEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}
