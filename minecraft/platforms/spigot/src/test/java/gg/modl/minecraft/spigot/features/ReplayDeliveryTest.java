package gg.modl.minecraft.spigot.features;

import gg.modl.minecraft.core.service.ReplayCaptureResult;
import gg.modl.minecraft.core.service.ReplayCaptureStatus;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.features.reporter.ModlBackendReplayUploader;
import gg.modl.minecraft.replay.recording.CapturedReplay;
import gg.modl.minecraft.replay.recording.ReplayCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReplayDeliveryTest {
    private static final UUID TARGET = UUID.randomUUID();
    private static final PluginLogger LOGGER = new PluginLogger() {
        @Override public void info(String message) {}
        @Override public void warning(String message) {}
        @Override public void severe(String message) {}
    };

    @TempDir
    Path replays;

    @Test
    void anUploadedReplayIsDeletedLocally() throws Exception {
        File file = replay();
        ModlBackendReplayUploader uploader = mock(ModlBackendReplayUploader.class);
        when(uploader.uploadAsync(eq(file), anyString(), eq(TARGET), eq("Steve"))).thenReturn(CompletableFuture.completedFuture("replay-1"));

        ReplayCaptureResult result = deliver(uploader, file);

        assertEquals(ReplayCaptureStatus.OK, result.getStatus());
        assertEquals("replay-1", result.getReplayId());
        assertFalse(file.exists());
    }

    @Test
    void aFailedUploadDeletesTheReplayBecauseNothingRetriesIt() throws Exception {
        File file = replay();
        ModlBackendReplayUploader uploader = mock(ModlBackendReplayUploader.class);
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("storage refused"));
        when(uploader.uploadAsync(eq(file), anyString(), any(), any())).thenReturn(failed);

        assertEquals(ReplayCaptureStatus.ERROR, deliver(uploader, file).getStatus());
        assertFalse(file.exists());
    }

    @Test
    void aClosedUploaderDeletesTheReplay() throws Exception {
        File file = replay();
        ModlBackendReplayUploader uploader = mock(ModlBackendReplayUploader.class);
        when(uploader.uploadAsync(eq(file), anyString(), any(), any())).thenThrow(new RejectedExecutionException("closed"));

        assertEquals(ReplayCaptureStatus.ERROR, deliver(uploader, file).getStatus());
        assertFalse(file.exists());
    }

    @Test
    void aReplayWithoutAnUploaderIsDeleted() throws Exception {
        File file = replay();

        assertEquals(ReplayCaptureStatus.ERROR, deliver(null, file).getStatus());
        assertFalse(file.exists());
    }

    @Test
    void aRefusedCaptureReportsWhyWithoutAReplay() throws Exception {
        ReplayDelivery delivery = new ReplayDelivery(() -> null, "1.21.11", LOGGER);

        assertEquals(ReplayCaptureStatus.NO_ACTIVE_RECORDING, delivery.deliver(ReplayCapture.refused(ReplayCapture.Refusal.NOT_RECORDING),
                TARGET, "Steve").get(5, TimeUnit.SECONDS).getStatus());
        assertEquals(ReplayCaptureStatus.ERROR, delivery.deliver(ReplayCapture.refused(ReplayCapture.Refusal.MEMORY_BUDGET),
                TARGET, "Steve").get(5, TimeUnit.SECONDS).getStatus());
        assertFalse(delivery.available());
    }

    private ReplayCaptureResult deliver(ModlBackendReplayUploader uploader, File file) throws Exception {
        ReplayDelivery delivery = new ReplayDelivery(() -> uploader, "1.21.11", LOGGER);
        CapturedReplay replay = new CapturedReplay("local", file, 1_000, 10, file.length());
        return delivery.deliver(ReplayCapture.written(replay), TARGET, "Steve").get(5, TimeUnit.SECONDS);
    }

    private File replay() throws IOException {
        return Files.write(replays.resolve(UUID.randomUUID() + ".modlreplay"), new byte[]{1, 2, 3}).toFile();
    }
}
