package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.util.mappings.IRegistryHolder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Value
class RecordingSnapshot {
    UUID targetUuid;
    String targetName;
    long startEpochMs;
    long captureTime;
    WorldModel baseline;
    List<JournalBlock> blocks;
    Map<Long, StoredChunk> chunks;
    List<WorldSegment> segments;
    boolean hardcore;
    boolean truncated;
    @Nullable IRegistryHolder registries;
}
