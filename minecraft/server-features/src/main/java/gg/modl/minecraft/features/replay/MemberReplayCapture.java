package gg.modl.minecraft.features.replay;

import gg.modl.minecraft.core.network.ReplayCaptureHandler;
import gg.modl.minecraft.core.service.ReplayCaptureResult;
import gg.modl.minecraft.core.service.ReplayRecordingOutcome;
import gg.modl.minecraft.core.service.ReplayRecordingResult;
import gg.modl.minecraft.core.service.ReplayService;
import gg.modl.minecraft.core.util.Futures;
import gg.modl.minecraft.features.FeaturePlayerProvider;
import gg.modl.minecraft.core.util.PluginLogger;
import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

public final class MemberReplayCapture implements ReplayCaptureHandler {
    private static final Answers<ReplayCaptureResult> CAPTURE_ANSWERS = new Answers<>(
            ReplayCaptureResult.notLocal(), ReplayCaptureResult.noActiveRecording(), ReplayCaptureResult.error());
    private static final Answers<ReplayRecordingResult> RECORDING_ANSWERS = new Answers<>(
            ReplayRecordingResult.of(ReplayRecordingOutcome.NOT_LOCAL),
            ReplayRecordingResult.of(ReplayRecordingOutcome.NOT_AVAILABLE),
            ReplayRecordingResult.of(ReplayRecordingOutcome.ERROR));

    private final FeaturePlayerProvider players;
    private final Supplier<@Nullable ReplayService> recordings;
    private final PluginLogger logger;

    public MemberReplayCapture(FeaturePlayerProvider players, Supplier<@Nullable ReplayService> recordings, PluginLogger logger) {
        this.players = players;
        this.recordings = recordings;
        this.logger = logger;
    }

    @Override
    public CompletableFuture<ReplayCaptureResult> capture(UUID target, String targetName) {
        return delegate(target, targetName, CAPTURE_ANSWERS, service -> service.captureReplayResult(target, targetName));
    }

    @Override
    public CompletableFuture<ReplayRecordingResult> startRecording(UUID target, String targetName) {
        return delegate(target, targetName, RECORDING_ANSWERS, service -> service.startRecording(target, targetName));
    }

    @Override
    public CompletableFuture<ReplayRecordingResult> stopRecording(UUID target, String targetName) {
        return delegate(target, targetName, RECORDING_ANSWERS, service -> service.stopRecording(target, targetName));
    }

    @Override
    public CompletableFuture<ReplayRecordingResult> recordingStatus(UUID target, String targetName) {
        return delegate(target, targetName, RECORDING_ANSWERS, service -> service.recordingStatus(target, targetName));
    }

    private <T> CompletableFuture<T> delegate(UUID target, String targetName, Answers<T> answers,
                                              Function<ReplayService, CompletableFuture<T>> request) {
        CompletableFuture<T> result = new CompletableFuture<>();
        ReplayService replayService = recordings.get();
        if (!players.isOnline(target)) {
            result.complete(answers.notLocal);
            return result;
        }
        if (replayService == null) {
            result.complete(answers.unavailable);
            return result;
        }
        Futures.whenComplete(request.apply(replayService), (answer, failure) -> {
            if (failure != null || answer == null) {
                logger.warning("[replay] Replay request failed for " + targetName
                        + (failure == null ? "" : ": " + failure.getMessage()));
                result.complete(answers.error);
                return;
            }
            result.complete(answer);
        }, logger);
        return result;
    }

    @Value
    private static class Answers<T> {
        T notLocal;
        T unavailable;
        T error;
    }
}
