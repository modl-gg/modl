package gg.modl.minecraft.replay.recording;

import gg.modl.minecraft.replay.format.ReplayLimits;
import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
class ReplayBounds {
    static final ReplayBounds UPLOAD = ReplayBounds.builder()
            .compressedBytes(ReplayLimits.MAX_COMPRESSED_BYTES)
            .uncompressedBytes(ReplayLimits.MAX_UNCOMPRESSED_BYTES)
            .sections(ReplayLimits.MAX_SECTIONS)
            .events(ReplayLimits.MAX_EVENTS)
            .keyframes(ReplayLimits.MAX_KEYFRAMES)
            .blockStates(ReplayLimits.MAX_BLOCK_STATES)
            .strings(ReplayLimits.MAX_STRINGS)
            .blockEntities(ReplayLimits.MAX_BLOCK_ENTITIES)
            .build();

    long compressedBytes;
    long uncompressedBytes;
    int sections;
    long events;
    int keyframes;
    int blockStates;
    int strings;
    int blockEntities;
}
