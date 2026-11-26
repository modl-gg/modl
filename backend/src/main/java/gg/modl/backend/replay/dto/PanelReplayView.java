package gg.modl.backend.replay.dto;

import org.jspecify.annotations.Nullable;

public record PanelReplayView(
    String replayId,
    @Nullable String mcVersion,
    String status,
    long fileSize,
    long createdAt,
    @Nullable String targetUuid,
    @Nullable String targetName,
    boolean labeled,
    String replayUrl,
    @Nullable Integer formatVersion
) {}
