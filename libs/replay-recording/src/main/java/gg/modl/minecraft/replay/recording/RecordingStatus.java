package gg.modl.minecraft.replay.recording;

import lombok.Value;

@Value
public class RecordingStatus {
    RecordingCause cause;
    long recordingMillis;
}
