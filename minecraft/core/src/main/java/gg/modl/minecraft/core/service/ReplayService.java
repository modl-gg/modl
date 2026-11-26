package gg.modl.minecraft.core.service;

import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface ReplayService {
    CompletableFuture<ReplayCaptureResult> captureReplayResult(UUID targetUuid, String targetName);

    CompletableFuture<ReplayRecordingResult> startRecording(UUID targetUuid, String targetName);

    CompletableFuture<ReplayRecordingResult> stopRecording(UUID targetUuid, String targetName);

    CompletableFuture<ReplayRecordingResult> recordingStatus(UUID targetUuid, String targetName);

    default CompletableFuture<@Nullable String> captureReplay(UUID targetUuid, String targetName) {
        return captureReplayResult(targetUuid, targetName).thenApply(result ->
                result.getStatus() == ReplayCaptureStatus.OK ? result.getReplayId() : null);
    }

    default ReplayCaptureStatus getReplayStatus(UUID playerUuid) {
        return ReplayCaptureStatus.NO_ACTIVE_RECORDING;
    }

    default boolean isReplayAvailable(UUID playerUuid) {
        return getReplayStatus(playerUuid) == ReplayCaptureStatus.OK;
    }

    default boolean shouldAttemptCapture(UUID playerUuid) {
        return getReplayStatus(playerUuid) != ReplayCaptureStatus.NO_SERVER_CONNECTED;
    }

    default void recordOnSuspicion(UUID playerUuid) {}

    default void onPlayerDisconnect(UUID playerUuid) {}
}
