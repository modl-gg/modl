package gg.modl.minecraft.core.network.link;

import gg.modl.minecraft.core.service.ActiveRecording;
import gg.modl.minecraft.core.service.RecordingOrigin;
import gg.modl.minecraft.core.service.ReplayMemory;
import gg.modl.minecraft.core.service.ReplayRecordingOutcome;
import gg.modl.minecraft.core.service.ReplayRecordingResult;
import gg.modl.proto.modl.v1.ReplayCaptureOutcome;
import gg.modl.proto.modl.v1.ReplayCaptureResult;
import gg.modl.proto.modl.v1.ReplayRecordingOrigin;
import gg.modl.proto.modl.v1.ReplayRecordingState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplayRecordingProtosTest {
    @Test
    void everyOutcomeAndOriginSurvivesTheWire() {
        for (ReplayRecordingOutcome outcome : ReplayRecordingOutcome.values()) {
            for (RecordingOrigin origin : RecordingOrigin.values()) {
                ReplayRecordingResult result = ReplayRecordingResult.of(outcome, new ActiveRecording(origin, 42L),
                        new ReplayMemory(1L, 2L, 3));

                assertEquals(result, ReplayRecordingProtos.fromResult(ReplayRecordingProtos.toResult("r", result)));
            }
        }
    }

    @Test
    void hubOutcomesMapOntoTheRecordingOutcomes() {
        assertEquals(ReplayRecordingOutcome.ERROR, outcomeOf(ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_TIMED_OUT));
        assertEquals(ReplayRecordingOutcome.NO_SERVER_CONNECTED, outcomeOf(ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_MEMBER_DISCONNECTED));
        assertEquals(ReplayRecordingOutcome.NO_SERVER_CONNECTED, outcomeOf(ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_NO_MEMBER));
    }

    @Test
    void aRecordingWithoutAKnownOriginIsAnError() {
        ReplayCaptureResult unknown = ReplayCaptureResult.newBuilder().setRequestId("r")
                .setOutcome(ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_OK)
                .setRecording(ReplayRecordingState.newBuilder().setOrigin(ReplayRecordingOrigin.REPLAY_RECORDING_ORIGIN_UNSPECIFIED))
                .build();

        assertEquals(ReplayRecordingResult.of(ReplayRecordingOutcome.ERROR), ReplayRecordingProtos.fromResult(unknown));
    }

    private static ReplayRecordingOutcome outcomeOf(ReplayCaptureOutcome outcome) {
        return ReplayRecordingProtos.fromResult(ReplayCaptureResult.newBuilder().setRequestId("r").setOutcome(outcome).build())
                .getOutcome();
    }
}
