package gg.modl.minecraft.replay.recording;

public interface RecordingConfig {
    int bufferDurationSeconds();
    int maxDurationSeconds();
    int radiusBlocks();
    int moveThrottleMs();
    String mcVersion();
}
