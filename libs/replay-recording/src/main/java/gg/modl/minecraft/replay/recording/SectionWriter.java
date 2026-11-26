package gg.modl.minecraft.replay.recording;

import com.google.protobuf.ByteString;
import gg.modl.proto.modl.v1.ReplayFrame;
import gg.modl.proto.modl.v1.ReplayWorldSection;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

final class SectionWriter {
    private final Map<Long, StoredChunk> chunks;
    private final Map<Integer, WorldSegment> segments;
    private final PathTracker path;
    private final StoredPacketDecoder decoder;
    private final BiomeNames biomes;
    private final int radiusChunks;
    private final Logger logger;
    private final Set<Long> written = new HashSet<>();
    private long sectionCount;

    SectionWriter(Map<Long, StoredChunk> chunks, Map<Integer, WorldSegment> segments, PathTracker path, StoredPacketDecoder decoder,
                  BiomeNames biomes, int radiusBlocks, Logger logger) {
        this.chunks = chunks;
        this.segments = segments;
        this.path = path;
        this.decoder = decoder;
        this.biomes = biomes;
        this.radiusChunks = RadiusChunks.of(radiusBlocks);
        this.logger = logger;
    }

    void write(BoundedReplayWriter writer, FileTables tables) throws IOException {
        for (Map.Entry<Long, StoredChunk> entry : chunks.entrySet()) {
            long key = entry.getKey();
            WorldSegment segment = segments.get(BlockPositions.chunkSegment(key));
            if (segment == null || !path.includes(key, radiusChunks)) continue;
            DecodedColumn column = decode(entry.getValue(), segment, key);
            if (column == null) continue;
            written.add(key);
            for (DecodedSection section : column.sections()) {
                if (section.isEmpty()) continue;
                ReplayFrame frame = ReplayFrame.newBuilder().setSection(section(segment.getId(), column, section, tables)).build();
                tables.flush(writer);
                writer.write(frame);
                sectionCount++;
            }
        }
    }

    boolean wrote(int segment, int x, int z) {
        return written.contains(BlockPositions.chunkKey(segment, x >> 4, z >> 4));
    }

    long sectionCount() {
        return sectionCount;
    }

    private @Nullable DecodedColumn decode(StoredChunk chunk, WorldSegment segment, long key) {
        try {
            DecodedColumn column = decoder.decode(chunk, segment);
            chunk.overlay().forEach((position, state) ->
                    column.set(BlockPositions.x(position), BlockPositions.y(position), BlockPositions.z(position), state));
            for (PendingChange change : path.pendingFor(key)) {
                if (change.getTime() > chunk.receivedAt()) column.set(change.getX(), change.getY(), change.getZ(), change.getState());
            }
            return column;
        } catch (Exception e) {
            logger.log(Level.FINE, "[replay] Skipping a stored chunk that cannot be decoded", e);
            return null;
        }
    }

    private ReplayWorldSection section(int world, DecodedColumn column, DecodedSection section, FileTables tables) {
        ReplayWorldSection.Builder builder = ReplayWorldSection.newBuilder()
                .setWorld(world)
                .setX(column.chunkX())
                .setY(section.getSectionY())
                .setZ(column.chunkZ());
        int[] states = section.getStates();
        if (states != null && !section.isAir()) {
            for (int state : states) builder.addStates(tables.block(state));
        }
        byte[] sky = section.getSkyLight();
        if (sky != null) builder.setSkyLight(ByteString.copyFrom(sky));
        byte[] block = section.getBlockLight();
        if (block != null) builder.setBlockLight(ByteString.copyFrom(block));
        int[] biomeIds = section.getBiomes();
        if (biomeIds != null) {
            for (int biome : biomeIds) builder.addBiomes(tables.literal(biomes.name(biome)));
        }
        return builder.build();
    }
}
