package gg.modl.minecraft.core.service;

import lombok.Value;

@Value
public class ActiveRecording {
    RecordingOrigin origin;
    long recordingMillis;
}
