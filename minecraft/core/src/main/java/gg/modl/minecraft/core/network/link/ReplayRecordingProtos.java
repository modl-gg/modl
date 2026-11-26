package gg.modl.minecraft.core.network.link;

import gg.modl.minecraft.core.service.ActiveRecording;
import gg.modl.minecraft.core.service.RecordingOrigin;
import gg.modl.minecraft.core.service.ReplayMemory;
import gg.modl.minecraft.core.service.ReplayRecordingOutcome;
import gg.modl.minecraft.core.service.ReplayRecordingResult;
import gg.modl.proto.modl.v1.ReplayCaptureOutcome;
import gg.modl.proto.modl.v1.ReplayCaptureResult;
import gg.modl.proto.modl.v1.ReplayMemoryUse;
import gg.modl.proto.modl.v1.ReplayRecordingOrigin;
import gg.modl.proto.modl.v1.ReplayRecordingState;
import org.jspecify.annotations.Nullable;

final class ReplayRecordingProtos {
    private ReplayRecordingProtos() {
    }

    static ReplayCaptureResult toResult(String requestId, ReplayRecordingResult result) {
        ReplayCaptureResult.Builder answer = ReplayCaptureResult.newBuilder()
                .setRequestId(requestId)
                .setOutcome(outcomeOf(result.getOutcome()));
        ActiveRecording recording = result.getRecording();
        if (recording != null) {
            answer.setRecording(ReplayRecordingState.newBuilder()
                    .setOrigin(protoOriginOf(recording.getOrigin()))
                    .setRecordingMs(Math.max(0L, recording.getRecordingMillis())));
        }
        ReplayMemory memory = result.getMemory();
        if (memory != null) {
            answer.setMemory(ReplayMemoryUse.newBuilder()
                    .setUsedBytes(Math.max(0L, memory.getUsedBytes()))
                    .setLimitBytes(Math.max(0L, memory.getLimitBytes()))
                    .setRecordings(Math.max(0, memory.getRecordings())));
        }
        return answer.build();
    }

    static ReplayRecordingResult fromResult(ReplayCaptureResult result) {
        ActiveRecording recording = null;
        if (result.hasRecording()) {
            RecordingOrigin origin = originOf(result.getRecording().getOrigin());
            if (origin == null) return ReplayRecordingResult.of(ReplayRecordingOutcome.ERROR);
            recording = new ActiveRecording(origin, result.getRecording().getRecordingMs());
        }
        ReplayMemory memory = result.hasMemory()
                ? new ReplayMemory(result.getMemory().getUsedBytes(), result.getMemory().getLimitBytes(),
                        result.getMemory().getRecordings())
                : null;
        return ReplayRecordingResult.of(outcomeOf(result.getOutcome()), recording, memory);
    }

    static ReplayCaptureOutcome outcomeOf(ReplayRecordingOutcome outcome) {
        switch (outcome) {
            case OK:
                return ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_OK;
            case ALREADY_RECORDING:
                return ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_ALREADY_RECORDING;
            case NOT_RECORDING:
                return ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_NO_ACTIVE_RECORDING;
            case NOT_STARTED_BY_STAFF:
                return ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_NOT_STARTED_BY_STAFF;
            case NOT_LOCAL:
                return ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_NOT_HOSTED;
            case NO_SERVER_CONNECTED:
                return ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_NO_MEMBER;
            case NOT_AVAILABLE:
                return ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_NOT_AVAILABLE;
            default:
                return ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_ERROR;
        }
    }

    static ReplayRecordingOutcome outcomeOf(ReplayCaptureOutcome outcome) {
        switch (outcome) {
            case REPLAY_CAPTURE_OUTCOME_OK:
                return ReplayRecordingOutcome.OK;
            case REPLAY_CAPTURE_OUTCOME_ALREADY_RECORDING:
                return ReplayRecordingOutcome.ALREADY_RECORDING;
            case REPLAY_CAPTURE_OUTCOME_NO_ACTIVE_RECORDING:
                return ReplayRecordingOutcome.NOT_RECORDING;
            case REPLAY_CAPTURE_OUTCOME_NOT_STARTED_BY_STAFF:
                return ReplayRecordingOutcome.NOT_STARTED_BY_STAFF;
            case REPLAY_CAPTURE_OUTCOME_NOT_HOSTED:
                return ReplayRecordingOutcome.NOT_LOCAL;
            case REPLAY_CAPTURE_OUTCOME_NO_MEMBER:
            case REPLAY_CAPTURE_OUTCOME_MEMBER_DISCONNECTED:
                return ReplayRecordingOutcome.NO_SERVER_CONNECTED;
            case REPLAY_CAPTURE_OUTCOME_NOT_AVAILABLE:
                return ReplayRecordingOutcome.NOT_AVAILABLE;
            default:
                return ReplayRecordingOutcome.ERROR;
        }
    }

    private static ReplayRecordingOrigin protoOriginOf(RecordingOrigin origin) {
        switch (origin) {
            case AUTO_RECORD:
                return ReplayRecordingOrigin.REPLAY_RECORDING_ORIGIN_AUTO_RECORD;
            case ANTICHEAT_FLAG:
                return ReplayRecordingOrigin.REPLAY_RECORDING_ORIGIN_ANTICHEAT_FLAG;
            default:
                return ReplayRecordingOrigin.REPLAY_RECORDING_ORIGIN_STAFF;
        }
    }

    private static @Nullable RecordingOrigin originOf(ReplayRecordingOrigin origin) {
        switch (origin) {
            case REPLAY_RECORDING_ORIGIN_AUTO_RECORD:
                return RecordingOrigin.AUTO_RECORD;
            case REPLAY_RECORDING_ORIGIN_ANTICHEAT_FLAG:
                return RecordingOrigin.ANTICHEAT_FLAG;
            case REPLAY_RECORDING_ORIGIN_STAFF:
                return RecordingOrigin.STAFF;
            default:
                return null;
        }
    }
}
