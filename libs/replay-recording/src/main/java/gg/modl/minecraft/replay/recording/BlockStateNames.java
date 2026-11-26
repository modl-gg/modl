package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateValue;
import gg.modl.proto.modl.v1.ReplayBlockProperty;
import gg.modl.proto.modl.v1.ReplayBlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class BlockStateNames {
    static final String UNKNOWN = "minecraft:unknown";
    private static final String AIR = "minecraft:air";
    private static final int MAX_CACHED = 65536;

    private final ClientVersion version;
    private final Map<Integer, ReplayBlockState> cache = new ConcurrentHashMap<>();

    BlockStateNames(ClientVersion version) {
        this.version = version;
    }

    static ReplayBlockState air() {
        return ReplayBlockState.newBuilder().setName(AIR).build();
    }

    ReplayBlockState state(int globalId) {
        ReplayBlockState known = cache.get(globalId);
        if (known != null) return known;
        ReplayBlockState resolved = resolve(globalId);
        if (cache.size() < MAX_CACHED) cache.put(globalId, resolved);
        return resolved;
    }

    private ReplayBlockState resolve(int globalId) {
        try {
            WrappedBlockState state = WrappedBlockState.getByGlobalId(version, globalId, false);
            String name = state.getType().getName();
            ReplayBlockState.Builder builder = ReplayBlockState.newBuilder().setName(name.indexOf(':') > 0 ? name : "minecraft:" + name);
            List<ReplayBlockProperty> properties = new ArrayList<>();
            for (Map.Entry<StateValue, Object> entry : state.getInternalData().entrySet()) {
                properties.add(ReplayBlockProperty.newBuilder()
                        .setName(entry.getKey().getName().toLowerCase(Locale.ROOT))
                        .setValue(String.valueOf(entry.getValue()).toLowerCase(Locale.ROOT))
                        .build());
            }
            properties.sort((a, b) -> a.getName().compareTo(b.getName()));
            return builder.addAllProperties(properties).build();
        } catch (RuntimeException e) {
            return ReplayBlockState.newBuilder().setName(UNKNOWN)
                    .addProperties(ReplayBlockProperty.newBuilder().setName("id").setValue(Integer.toString(globalId)))
                    .build();
        }
    }
}
