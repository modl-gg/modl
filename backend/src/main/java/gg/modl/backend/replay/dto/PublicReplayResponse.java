package gg.modl.backend.replay.dto;

import org.jspecify.annotations.Nullable;

public record PublicReplayResponse(
    String replayId,
    @Nullable String mcVersion,
    long fileSize,
    long timestamp,
    String replayUrl,
    String status,
    boolean labeled
) {}
