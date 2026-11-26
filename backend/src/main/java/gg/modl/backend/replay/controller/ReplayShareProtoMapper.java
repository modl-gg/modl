package gg.modl.backend.replay.controller;

import static gg.modl.backend.infrastructure.proto.ProtoMapperSupport.nullToEmpty;
import static gg.modl.backend.infrastructure.proto.ProtoMapperSupport.toTimestamp;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.replay.data.ReplayShare;
import gg.modl.backend.replay.share.CreatedReplayShare;
import gg.modl.backend.replay.share.ReplayShareExpiry;
import gg.modl.backend.replay.share.SharedReplay;
import gg.modl.proto.modl.v1.CreateReplayShareResponse;
import gg.modl.proto.modl.v1.ReplaySharesResponse;
import gg.modl.proto.modl.v1.SharedReplayResponse;
import java.time.Instant;
import java.util.List;

final class ReplayShareProtoMapper {
    private ReplayShareProtoMapper() {
    }

    static ReplayShareExpiry expiryOf(gg.modl.proto.modl.v1.ReplayShareExpiry expiry) {
        return switch (expiry) {
            case REPLAY_SHARE_EXPIRY_ONE_DAY -> ReplayShareExpiry.ONE_DAY;
            case REPLAY_SHARE_EXPIRY_SEVEN_DAYS -> ReplayShareExpiry.SEVEN_DAYS;
            case REPLAY_SHARE_EXPIRY_THIRTY_DAYS -> ReplayShareExpiry.THIRTY_DAYS;
            case REPLAY_SHARE_EXPIRY_NEVER -> ReplayShareExpiry.NEVER;
            case REPLAY_SHARE_EXPIRY_UNSPECIFIED, UNRECOGNIZED -> throw new ValidationException("Choose when the link expires");
        };
    }

    static CreateReplayShareResponse toCreated(CreatedReplayShare created) {
        return CreateReplayShareResponse.newBuilder()
            .setShare(toProto(created.share()))
            .setToken(created.token())
            .setUrl(created.url())
            .build();
    }

    static ReplaySharesResponse toList(List<ReplayShare> shares) {
        ReplaySharesResponse.Builder response = ReplaySharesResponse.newBuilder();
        shares.forEach(share -> response.addShares(toProto(share)));
        return response.build();
    }

    static SharedReplayResponse toShared(SharedReplay shared) {
        ReplayDocument replay = shared.replay();
        return SharedReplayResponse.newBuilder()
            .setTargetName(nullToEmpty(replay.getTargetName()))
            .setServerName(shared.serverName())
            .setMcVersion(nullToEmpty(replay.getMcVersion()))
            .setCreatedAt(toTimestamp(replay.getCreatedAt()))
            .setFormatVersion(replay.getFormatVersion() == null ? 0 : replay.getFormatVersion())
            .setFileSize(replay.getFileSize())
            .setFileUrl(shared.fileUrl())
            .build();
    }

    private static gg.modl.proto.modl.v1.ReplayShare toProto(ReplayShare share) {
        gg.modl.proto.modl.v1.ReplayShare.Builder proto = gg.modl.proto.modl.v1.ReplayShare.newBuilder()
            .setId(share.id())
            .setCreatedBy(share.createdBy())
            .setCreatedAt(toTimestamp(share.createdAt()))
            .setViewCount(share.viewCount());
        Instant expiresAt = share.expiresAt();
        if (expiresAt != null) {
            proto.setExpiresAt(toTimestamp(expiresAt));
        }
        Instant revokedAt = share.revokedAt();
        if (revokedAt != null) {
            proto.setRevokedAt(toTimestamp(revokedAt));
        }
        Instant lastViewedAt = share.lastViewedAt();
        if (lastViewedAt != null) {
            proto.setLastViewedAt(toTimestamp(lastViewedAt));
        }
        return proto.build();
    }
}
