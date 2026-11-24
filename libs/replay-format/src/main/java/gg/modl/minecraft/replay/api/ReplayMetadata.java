package gg.modl.minecraft.replay.api;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.io.File;

@Value
@Builder
public class ReplayMetadata {
    long durationMs;
    long eventCount;
    long fileSizeBytes;
    @Nullable File outputFile;
}
