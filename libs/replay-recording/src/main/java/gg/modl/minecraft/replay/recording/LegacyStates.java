package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import org.jspecify.annotations.Nullable;

final class LegacyStates {
    private final boolean legacyIds;
    private final int fallingBlockType;

    LegacyStates(ServerVersion version, int fallingBlockType) {
        this.legacyIds = version.isOlderThan(ServerVersion.V_1_13);
        this.fallingBlockType = fallingBlockType;
    }

    @Nullable Integer fallingBlockState(ModelEntity entity) {
        if (entity.getType() != fallingBlockType) return null;
        int data = entity.getData();
        if (!legacyIds) return data;
        int blockId = data & 0xFFF;
        int meta = (data >>> 12) & 0xF;
        return (blockId << 4) | meta;
    }
}
