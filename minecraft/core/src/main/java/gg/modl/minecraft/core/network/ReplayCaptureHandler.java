package gg.modl.minecraft.core.network;

import gg.modl.minecraft.core.service.ReplayCaptureResult;
import gg.modl.minecraft.core.service.ReplayRecordingResult;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface ReplayCaptureHandler {
    CompletableFuture<ReplayCaptureResult> capture(UUID target, String targetName);

    CompletableFuture<ReplayRecordingResult> startRecording(UUID target, String targetName);

    CompletableFuture<ReplayRecordingResult> stopRecording(UUID target, String targetName);

    CompletableFuture<ReplayRecordingResult> recordingStatus(UUID target, String targetName);
}
