package gg.modl.minecraft.features.replay;

import gg.modl.minecraft.core.service.ReplayCaptureResult;
import gg.modl.minecraft.core.service.ReplayCaptureStatus;
import gg.modl.minecraft.core.service.ReplayRecordingOutcome;
import gg.modl.minecraft.core.service.ReplayRecordingResult;
import gg.modl.minecraft.core.service.ReplayService;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class SwitchableReplayService implements ReplayService {
    private static final ReplayRecordingResult NOT_AVAILABLE = ReplayRecordingResult.of(ReplayRecordingOutcome.NOT_AVAILABLE);

    private volatile @Nullable ReplayService current;

    public void use(@Nullable ReplayService recorder) {
        current = recorder;
    }

    public boolean isActive() {
        return current != null;
    }

    @Override
    public CompletableFuture<ReplayCaptureResult> captureReplayResult(UUID targetUuid, String targetName) {
        ReplayService recorder = current;
        return recorder == null ? CompletableFuture.completedFuture(ReplayCaptureResult.noActiveRecording())
                : recorder.captureReplayResult(targetUuid, targetName);
    }

    @Override
    public CompletableFuture<ReplayRecordingResult> startRecording(UUID targetUuid, String targetName) {
        ReplayService recorder = current;
        return recorder == null ? CompletableFuture.completedFuture(NOT_AVAILABLE) : recorder.startRecording(targetUuid, targetName);
    }

    @Override
    public CompletableFuture<ReplayRecordingResult> stopRecording(UUID targetUuid, String targetName) {
        ReplayService recorder = current;
        return recorder == null ? CompletableFuture.completedFuture(NOT_AVAILABLE) : recorder.stopRecording(targetUuid, targetName);
    }

    @Override
    public CompletableFuture<ReplayRecordingResult> recordingStatus(UUID targetUuid, String targetName) {
        ReplayService recorder = current;
        return recorder == null ? CompletableFuture.completedFuture(NOT_AVAILABLE) : recorder.recordingStatus(targetUuid, targetName);
    }

    @Override
    public ReplayCaptureStatus getReplayStatus(UUID playerUuid) {
        ReplayService recorder = current;
        return recorder == null ? ReplayCaptureStatus.NO_ACTIVE_RECORDING : recorder.getReplayStatus(playerUuid);
    }

    @Override
    public void recordOnSuspicion(UUID playerUuid) {
        ReplayService recorder = current;
        if (recorder != null) recorder.recordOnSuspicion(playerUuid);
    }

    @Override
    public void onPlayerDisconnect(UUID playerUuid) {
        ReplayService recorder = current;
        if (recorder != null) recorder.onPlayerDisconnect(playerUuid);
    }
}
