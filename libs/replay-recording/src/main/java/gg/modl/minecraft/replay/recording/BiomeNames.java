package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.protocol.mapper.MappedEntity;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.biome.Biomes;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.util.mappings.IRegistry;
import com.github.retrooper.packetevents.util.mappings.IRegistryHolder;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

final class BiomeNames {
    private static final ResourceLocation BIOME_REGISTRY = ResourceLocation.minecraft("worldgen/biome");
    private static final String FALLBACK = "minecraft:plains";

    private final ClientVersion version;
    private final @Nullable IRegistry<?> synced;
    private final Map<Integer, String> cache = new HashMap<>();

    BiomeNames(ClientVersion version, @Nullable IRegistryHolder registries) {
        this.version = version;
        this.synced = registries == null ? null : registryOf(registries, version);
    }

    String name(int id) {
        String known = cache.get(id);
        if (known != null) return known;
        String resolved = resolve(id);
        cache.put(id, resolved);
        return resolved;
    }

    private String resolve(int id) {
        MappedEntity entity = lookup(synced, id);
        if (entity == null) entity = lookup(Biomes.getRegistry(), id);
        return entity == null ? FALLBACK : entity.getName().toString();
    }

    private @Nullable MappedEntity lookup(@Nullable IRegistry<?> registry, int id) {
        if (registry == null) return null;
        try {
            return registry.getById(version, id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static @Nullable IRegistry<?> registryOf(IRegistryHolder registries, ClientVersion version) {
        try {
            return registries.getRegistry(BIOME_REGISTRY, version);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
