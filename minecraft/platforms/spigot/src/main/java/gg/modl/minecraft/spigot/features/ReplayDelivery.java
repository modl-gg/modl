package gg.modl.minecraft.spigot.features;

import gg.modl.minecraft.core.service.ReplayCaptureResult;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.features.reporter.ModlBackendReplayUploader;
import gg.modl.minecraft.replay.recording.CapturedReplay;
import gg.modl.minecraft.replay.recording.ReplayCapture;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

final class ReplayDelivery {
    private final Supplier<@Nullable ModlBackendReplayUploader> uploader;
    private final String minecraftVersion;
    private final PluginLogger logger;

    ReplayDelivery(Supplier<@Nullable ModlBackendReplayUploader> uploader, String minecraftVersion, PluginLogger logger) {
        this.uploader = uploader;
        this.minecraftVersion = minecraftVersion;
        this.logger = logger;
    }

    boolean available() {
        return uploader.get() != null;
    }

    CompletableFuture<ReplayCaptureResult> deliver(ReplayCapture capture, UUID targetUuid, String targetName) {
        CapturedReplay replay = capture.getReplay();
        ReplayCapture.Refusal refusal = capture.getRefusal();
        if (replay == null) {
            if (refusal == ReplayCapture.Refusal.NOT_RECORDING) return CompletableFuture.completedFuture(ReplayCaptureResult.noActiveRecording());
            logger.warning("[replay] No replay of " + targetName + " was captured: "
                    + (refusal == null ? ReplayCapture.Refusal.WRITE_FAILED.reason() : refusal.reason()));
            return CompletableFuture.completedFuture(ReplayCaptureResult.error());
        }
        File file = replay.getFile();
        ModlBackendReplayUploader current = uploader.get();
        if (current == null) return discarded(file, targetName, "the replay uploader is not connected");
        try {
            return current.uploadAsync(file, minecraftVersion, targetUuid, targetName).handle((replayId, failure) -> {
                delete(file);
                if (failure == null) return ReplayCaptureResult.ok(replayId);
                logger.warning("[replay] Replay upload failed for " + targetName + ": " + failure.getMessage());
                return ReplayCaptureResult.error();
            });
        } catch (RejectedExecutionException e) {
            return discarded(file, targetName, "the replay uploader is closed");
        }
    }

    private CompletableFuture<ReplayCaptureResult> discarded(File file, String targetName, String reason) {
        delete(file);
        logger.warning("[replay] The replay of " + targetName + " was not uploaded: " + reason);
        return CompletableFuture.completedFuture(ReplayCaptureResult.error());
    }

    private void delete(File file) {
        if (file.exists() && !file.delete()) logger.debug("[replay] Could not delete " + file);
    }
}
