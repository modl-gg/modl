package gg.modl.minecraft.spigot.features;

import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.replay.recording.RecordingSeed;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

final class NearbyEntitySeeder {
    static final int MAX_SEEDED_ENTITIES = 256;

    private final EntityStates states;
    private final PluginLogger logger;

    NearbyEntitySeeder(EntityStates states, PluginLogger logger) {
        this.states = states;
        this.logger = logger;
    }

    void seed(RecordingSeed seed, Player target, int radiusBlocks) {
        Location center = target.getLocation();
        int horizontal = Math.min(radiusBlocks, states.viewDistanceBlocks(target));
        int seeded = 0;
        int skipped = 0;
        for (Entity entity : target.getNearbyEntities(horizontal, radiusBlocks, horizontal)) {
            if (seeded >= MAX_SEEDED_ENTITIES) break;
            try {
                if (tracked(center, entity, horizontal) && seedOne(seed, target, entity)) seeded++;
            } catch (RuntimeException unreadable) {
                skipped++;
            }
        }
        if (skipped > 0) logger.debug("[replay] Skipped " + skipped + " entities that could not be read when recording " + target.getName());
    }

    private boolean tracked(Location center, Entity entity, int horizontal) {
        int range = Math.min(horizontal, states.trackingRange(entity));
        Location location = entity.getLocation();
        return Math.abs(location.getX() - center.getX()) <= range && Math.abs(location.getZ() - center.getZ()) <= range;
    }

    private boolean seedOne(RecordingSeed seed, Player target, Entity entity) {
        return entity instanceof Player ? seedPlayer(seed, target, (Player) entity) : seedEntity(seed, entity);
    }

    private boolean seedPlayer(RecordingSeed seed, Player target, Player other) {
        if (!target.canSee(other)) return false;
        Location location = other.getLocation();
        seed.player(other.getEntityId(), other.getUniqueId(), other.getName(), states.profile(other),
                ReplayGameMode.of(other.getGameMode()), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch(), states.metadata(other), states.equipment(other));
        return true;
    }

    private boolean seedEntity(RecordingSeed seed, Entity entity) {
        EntityType type = states.type(entity);
        if (type == null) return false;
        Location location = entity.getLocation();
        seed.entity(entity.getEntityId(), type, entity.getUniqueId(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch(), states.metadata(entity), states.equipment(entity));
        return true;
    }
}
