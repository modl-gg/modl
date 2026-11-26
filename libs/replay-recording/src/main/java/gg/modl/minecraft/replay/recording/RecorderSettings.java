package gg.modl.minecraft.replay.recording;

import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class RecorderSettings {
    int windowSeconds;
    int radiusBlocks;
    long memoryBudgetBytes;
    String minecraftVersion;
    int dataVersion;
    String serverSoftware;
    String recorderVersion;
}
