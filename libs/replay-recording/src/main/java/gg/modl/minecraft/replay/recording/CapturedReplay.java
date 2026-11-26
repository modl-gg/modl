package gg.modl.minecraft.replay.recording;

import lombok.Value;

import java.io.File;

@Value
public class CapturedReplay {
    String replayId;
    File file;
    long durationMs;
    long eventCount;
    long sizeBytes;
}
