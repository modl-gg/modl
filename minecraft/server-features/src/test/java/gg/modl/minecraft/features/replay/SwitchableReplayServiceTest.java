package gg.modl.minecraft.features.replay;

import gg.modl.minecraft.core.service.ReplayCaptureResult;
import gg.modl.minecraft.core.service.ReplayCaptureStatus;
import gg.modl.minecraft.core.service.ReplayRecordingOutcome;
import gg.modl.minecraft.core.service.ReplayRecordingResult;
import gg.modl.minecraft.core.service.ReplayService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwitchableReplayServiceTest {
    private static final UUID PLAYER = UUID.randomUUID();

    @Test
    void withoutARecorderEveryCallAnswersThatReplaysAreNotAvailable() {
        SwitchableReplayService replays = new SwitchableReplayService();

        assertFalse(replays.isActive());
        assertEquals(ReplayRecordingOutcome.NOT_AVAILABLE, replays.startRecording(PLAYER, "Target").join().getOutcome());
        assertEquals(ReplayRecordingOutcome.NOT_AVAILABLE, replays.stopRecording(PLAYER, "Target").join().getOutcome());
        assertEquals(ReplayRecordingOutcome.NOT_AVAILABLE, replays.recordingStatus(PLAYER, "Target").join().getOutcome());
        assertEquals(ReplayCaptureStatus.NO_ACTIVE_RECORDING, replays.captureReplayResult(PLAYER, "Target").join().getStatus());
        replays.recordOnSuspicion(PLAYER);
    }

    @Test
    void aRecorderInUseAnswersUntilItIsSwitchedOff() {
        SwitchableReplayService replays = new SwitchableReplayService();
        Recorder recorder = new Recorder();

        replays.use(recorder);
        replays.recordOnSuspicion(PLAYER);
        assertTrue(replays.isActive());
        assertEquals(ReplayCaptureStatus.OK, replays.getReplayStatus(PLAYER));
        assertEquals(ReplayRecordingOutcome.OK, replays.startRecording(PLAYER, "Target").join().getOutcome());

        replays.use(null);
        replays.recordOnSuspicion(PLAYER);
        assertEquals(1, recorder.suspicions.size());
        assertEquals(ReplayCaptureStatus.NO_ACTIVE_RECORDING, replays.getReplayStatus(PLAYER));
    }

    private static final class Recorder implements ReplayService {
        private final List<UUID> suspicions = new ArrayList<>();

        @Override
        public CompletableFuture<ReplayCaptureResult> captureReplayResult(UUID targetUuid, String targetName) {
            return CompletableFuture.completedFuture(ReplayCaptureResult.ok("replay"));
        }

        @Override
        public CompletableFuture<ReplayRecordingResult> startRecording(UUID targetUuid, String targetName) {
            return CompletableFuture.completedFuture(ReplayRecordingResult.of(ReplayRecordingOutcome.OK));
        }

        @Override
        public CompletableFuture<ReplayRecordingResult> stopRecording(UUID targetUuid, String targetName) {
            return CompletableFuture.completedFuture(ReplayRecordingResult.of(ReplayRecordingOutcome.OK));
        }

        @Override
        public CompletableFuture<ReplayRecordingResult> recordingStatus(UUID targetUuid, String targetName) {
            return CompletableFuture.completedFuture(ReplayRecordingResult.of(ReplayRecordingOutcome.OK));
        }

        @Override
        public ReplayCaptureStatus getReplayStatus(UUID playerUuid) {
            return ReplayCaptureStatus.OK;
        }

        @Override
        public void recordOnSuspicion(UUID playerUuid) {
            suspicions.add(playerUuid);
        }
    }
}
