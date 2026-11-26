package gg.modl.minecraft.core.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayServiceTest {
    @Test
    void shouldAttemptCaptureForColdUnknownTargetWhenItsServerIsConnected() {
        UUID targetUuid = UUID.randomUUID();
        StatusReplayService replayService = new StatusReplayService(ReplayCaptureStatus.NO_ACTIVE_RECORDING);

        assertTrue(replayService.shouldAttemptCapture(targetUuid));
    }

    @Test
    void shouldNotAttemptCaptureWithoutAConnectedServer() {
        UUID targetUuid = UUID.randomUUID();

        assertFalse(new StatusReplayService(ReplayCaptureStatus.NO_SERVER_CONNECTED).shouldAttemptCapture(targetUuid));
    }

    private static final class StatusReplayService implements ReplayService {
        private final ReplayCaptureStatus status;

        private StatusReplayService(ReplayCaptureStatus status) {
            this.status = status;
        }

        @Override
        public CompletableFuture<ReplayCaptureResult> captureReplayResult(UUID targetUuid, String targetName) {
            return CompletableFuture.completedFuture(ReplayCaptureResult.noActiveRecording());
        }

        @Override
        public ReplayCaptureStatus getReplayStatus(UUID playerUuid) {
            return status;
        }

        @Override
        public CompletableFuture<ReplayRecordingResult> startRecording(UUID targetUuid, String targetName) {
            return CompletableFuture.completedFuture(ReplayRecordingResult.of(ReplayRecordingOutcome.NOT_AVAILABLE));
        }

        @Override
        public CompletableFuture<ReplayRecordingResult> stopRecording(UUID targetUuid, String targetName) {
            return CompletableFuture.completedFuture(ReplayRecordingResult.of(ReplayRecordingOutcome.NOT_AVAILABLE));
        }

        @Override
        public CompletableFuture<ReplayRecordingResult> recordingStatus(UUID targetUuid, String targetName) {
            return CompletableFuture.completedFuture(ReplayRecordingResult.of(ReplayRecordingOutcome.NOT_AVAILABLE));
        }
    }
}
