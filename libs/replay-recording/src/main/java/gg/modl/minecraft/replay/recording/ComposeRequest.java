package gg.modl.minecraft.replay.recording;

import lombok.Value;
import lombok.With;

@Value
class ComposeRequest {
    String replayId;
    String captureReason;
    RecordingSnapshot snapshot;
    @With long windowMs;
    @With int radiusBlocks;
}
