package gg.modl.minecraft.core.network;

import gg.modl.minecraft.core.service.ReplayCaptureResult;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface ReplayCaptureHandler {
    CompletableFuture<ReplayCaptureResult> capture(UUID target, String targetName);
}
