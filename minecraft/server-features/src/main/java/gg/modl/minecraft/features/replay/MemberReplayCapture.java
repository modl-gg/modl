package gg.modl.minecraft.features.replay;

import gg.modl.minecraft.core.network.ReplayCaptureHandler;
import gg.modl.minecraft.core.service.ReplayCaptureResult;
import gg.modl.minecraft.core.service.ReplayService;
import gg.modl.minecraft.core.util.Futures;
import gg.modl.minecraft.features.FeaturePlayerProvider;
import gg.modl.minecraft.features.FeatureScheduler;
import gg.modl.minecraft.core.util.PluginLogger;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class MemberReplayCapture implements ReplayCaptureHandler {
    private final FeatureScheduler scheduler;
    private final FeaturePlayerProvider players;
    private final Supplier<ReplayService> recordings;
    private final PluginLogger logger;

    public MemberReplayCapture(FeatureScheduler scheduler, FeaturePlayerProvider players, Supplier<ReplayService> recordings,
                               PluginLogger logger) {
        this.scheduler = scheduler;
        this.players = players;
        this.recordings = recordings;
        this.logger = logger;
    }

    @Override
    public CompletableFuture<ReplayCaptureResult> capture(UUID target, String targetName) {
        CompletableFuture<ReplayCaptureResult> result = new CompletableFuture<>();
        scheduler.runOnMainThread(() -> {
            if (!players.isOnline(target)) {
                result.complete(ReplayCaptureResult.notLocal());
                return;
            }
            scheduler.runForPlayer(target, () -> captureOnPlayerThread(target, targetName, result));
        });
        return result;
    }

    private void captureOnPlayerThread(UUID target, String targetName, CompletableFuture<ReplayCaptureResult> result) {
        ReplayService replayService = recordings.get();
        if (!players.isOnline(target)) {
            result.complete(ReplayCaptureResult.notLocal());
            return;
        }
        if (replayService == null) {
            result.complete(ReplayCaptureResult.noActiveRecording());
            return;
        }
        Futures.whenComplete(replayService.captureReplayResult(target, targetName), (captured, failure) -> {
            if (failure != null) {
                logger.warning("[replay] Replay capture failed for " + targetName + ": " + failure.getMessage());
                result.complete(ReplayCaptureResult.error());
                return;
            }
            result.complete(captured);
        }, logger);
    }
}
