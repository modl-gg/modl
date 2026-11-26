package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.protocol.world.dimension.DimensionType;
import lombok.Value;
import org.jspecify.annotations.Nullable;

@Value
class WorldSegment {
    int id;
    String key;
    String worldName;
    int minY;
    int height;
    boolean skyLight;
    @Nullable DimensionType dimensionType;
    long startTime;

    boolean sameWorldAs(String otherKey, String otherWorldName) {
        return key.equals(otherKey) && worldName.equals(otherWorldName);
    }
}
