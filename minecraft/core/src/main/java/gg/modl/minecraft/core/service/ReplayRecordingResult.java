package gg.modl.minecraft.core.service;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;
import org.jspecify.annotations.Nullable;

@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ReplayRecordingResult {
    ReplayRecordingOutcome outcome;
    @Nullable ActiveRecording recording;
    @Nullable ReplayMemory memory;

    public static ReplayRecordingResult of(ReplayRecordingOutcome outcome) {
        return new ReplayRecordingResult(outcome, null, null);
    }

    public static ReplayRecordingResult of(ReplayRecordingOutcome outcome, @Nullable ActiveRecording recording,
                                           @Nullable ReplayMemory memory) {
        return new ReplayRecordingResult(outcome, recording, memory);
    }
}
