package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.netty.buffer.UnpooledByteBufAllocationHelper;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.LightData;
import com.github.retrooper.packetevents.protocol.world.chunk.NibbleArray3d;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v1_16.Chunk_v1_9;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v1_8.Chunk_v1_8;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.protocol.world.dimension.DimensionType;
import com.github.retrooper.packetevents.protocol.world.dimension.DimensionTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateLight;
import org.jspecify.annotations.Nullable;

import java.util.BitSet;
import java.util.UUID;
import java.util.zip.DataFormatException;

final class StoredPacketDecoder {
    private final ServerVersion serverVersion;

    StoredPacketDecoder(ServerVersion serverVersion) {
        this.serverVersion = serverVersion;
    }

    DecodedColumn decode(StoredChunk chunk, WorldSegment segment) throws DataFormatException {
        RawPacket packet = chunk.packet();
        byte[] raw = packet.inflate();
        DecodedColumn column;
        if (packet.kind() == RawPacket.LEGACY_BULK) {
            column = LegacyChunkFormat.decodeBulkEntry(raw, chunk.bulkIndex());
        } else if (packet.kind() == RawPacket.LEGACY_CHUNK) {
            column = LegacyChunkFormat.decodeSingle(raw);
        } else {
            column = decodeModern(raw, segment);
        }
        for (TimedPacket partial : chunk.partials()) {
            RawPacket partialPacket = partial.getPacket();
            byte[] partialRaw = partialPacket.inflate();
            column.merge(partialPacket.kind() == RawPacket.LEGACY_CHUNK
                    ? LegacyChunkFormat.decodeSingle(partialRaw) : decodeModern(partialRaw, segment));
        }
        for (TimedPacket update : chunk.lightUpdates()) {
            applyLight(column, lightOf(update.getPacket().inflate(), segment));
        }
        return column;
    }

    private DecodedColumn decodeModern(byte[] raw, WorldSegment segment) {
        ChunkPacket packet = new ChunkPacket(user(segment), serverVersion, raw);
        Column source = packet.getColumn();
        int minSection = Math.floorDiv(segment.getMinY(), 16);
        BaseChunk[] chunks = source.getChunks();
        DecodedColumn column = new DecodedColumn(source.getX(), source.getZ(), minSection, Math.max(chunks.length, segment.getHeight() >> 4));
        for (int i = 0; i < chunks.length; i++) {
            BaseChunk chunk = chunks[i];
            if (chunk == null) continue;
            int @Nullable [] states = chunk.isEmpty() ? null : new int[DecodedSection.BLOCKS];
            if (states != null) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            states[DecodedSection.index(x, y, z)] = chunk.getBlockId(x, y, z);
                        }
                    }
                }
            }
            DecodedSection section = new DecodedSection(minSection + i, states);
            sectionLight(section, chunk);
            if (chunk instanceof Chunk_v1_18) section.biomes(biomes(((Chunk_v1_18) chunk).getBiomeData()));
            column.put(section);
        }
        LightData light = packet.getLightData();
        if (light != null) applyLight(column, light);
        return column;
    }

    private static void sectionLight(DecodedSection section, BaseChunk chunk) {
        if (chunk instanceof Chunk_v1_9) {
            Chunk_v1_9 legacy = (Chunk_v1_9) chunk;
            section.light(data(legacy.getSkyLight()), data(legacy.getBlockLight()));
        } else if (chunk instanceof Chunk_v1_8) {
            Chunk_v1_8 legacy = (Chunk_v1_8) chunk;
            section.light(data(legacy.getSkyLight()), data(legacy.getBlockLight()));
        }
    }

    private static int[] biomes(@Nullable DataPalette palette) {
        if (palette == null) return new int[0];
        int[] ids = new int[DecodedSection.BIOMES];
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) ids[(y * 4 + z) * 4 + x] = palette.get(x, y, z);
            }
        }
        return ids;
    }

    private static byte @Nullable [] data(@Nullable NibbleArray3d array) {
        return array == null ? null : array.getData();
    }

    private LightData lightOf(byte[] raw, WorldSegment segment) {
        return new LightPacket(user(segment), serverVersion, raw).getLightData();
    }

    private static void applyLight(DecodedColumn column, LightData light) {
        int firstSection = column.minSection() - 1;
        applyMask(column, firstSection, light.getSkyLightMask(), light.getSkyLightArray(), true);
        applyMask(column, firstSection, light.getBlockLightMask(), light.getBlockLightArray(), false);
    }

    private static void applyMask(DecodedColumn column, int firstSection, @Nullable BitSet mask, byte @Nullable [][] arrays, boolean sky) {
        if (mask == null || arrays == null) return;
        int next = 0;
        for (int bit = mask.nextSetBit(0); bit >= 0 && next < arrays.length; bit = mask.nextSetBit(bit + 1)) {
            byte[] array = arrays[next++];
            DecodedSection section = column.section(firstSection + bit);
            if (section == null) continue;
            if (sky) section.light(array, section.getBlockLight());
            else section.light(section.getSkyLight(), array);
        }
    }

    private User user(WorldSegment segment) {
        User user = new User(null, ConnectionState.PLAY, serverVersion.toClientVersion(), new UserProfile(new UUID(0, 0), "replay"));
        DimensionType type = segment.getDimensionType();
        user.setDimensionType(type != null ? type : fallbackType(segment));
        return user;
    }

    private static DimensionType fallbackType(WorldSegment segment) {
        if (WorldPackets.NETHER.equals(segment.getKey())) return DimensionTypes.THE_NETHER;
        if (WorldPackets.END.equals(segment.getKey())) return DimensionTypes.THE_END;
        return segment.getMinY() < 0 ? DimensionTypes.OVERWORLD : DimensionTypes.OVERWORLD_PRE_1_18;
    }

    private static final class ChunkPacket extends WrapperPlayServerChunkData {
        ChunkPacket(User user, ServerVersion version, byte[] raw) {
            super(new Column(0, 0, true, new BaseChunk[0], new TileEntity[0]));
            this.user = user;
            this.serverVersion = version;
            this.clientVersion = version.toClientVersion();
            Object buffer = UnpooledByteBufAllocationHelper.wrappedBuffer(raw);
            this.buffer = buffer;
            try {
                read();
            } finally {
                ByteBufHelper.release(buffer);
            }
        }
    }

    private static final class LightPacket extends WrapperPlayServerUpdateLight {
        LightPacket(User user, ServerVersion version, byte[] raw) {
            super(0, 0, new LightData());
            this.user = user;
            this.serverVersion = version;
            this.clientVersion = version.toClientVersion();
            Object buffer = UnpooledByteBufAllocationHelper.wrappedBuffer(raw);
            this.buffer = buffer;
            try {
                read();
            } finally {
                ByteBufHelper.release(buffer);
            }
        }
    }
}
