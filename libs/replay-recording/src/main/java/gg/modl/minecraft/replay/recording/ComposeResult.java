package gg.modl.minecraft.replay.recording;

import lombok.Value;

@Value
class ComposeResult {
    long durationMs;
    long eventCount;
    long sectionCount;
}
