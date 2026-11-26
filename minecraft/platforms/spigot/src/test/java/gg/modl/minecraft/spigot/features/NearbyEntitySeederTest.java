package gg.modl.minecraft.spigot.features;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.replay.recording.RecordingSeed;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NearbyEntitySeederTest {
    private static final UserProfile ALEX = new UserProfile(UUID.randomUUID(), "Alex");
    private static final EntityType ZOMBIE = mock(EntityType.class);
    private static final PluginLogger LOGGER = new PluginLogger() {
        @Override public void info(String message) {}
        @Override public void warning(String message) {}
        @Override public void severe(String message) {}
    };

    @Test
    void trackedVisibleEntitiesAreSeededFromServerStateWithoutHidingOrShowingAnyone() {
        Player target = mock(Player.class);
        when(target.getLocation()).thenReturn(new Location(null, 0, 64, 0));
        Player visible = player(ALEX.getUUID(), "Alex", 11);
        Player hidden = player(UUID.randomUUID(), "Hidden", 12);
        Zombie zombie = zombie(13, 5);
        Zombie untracked = zombie(14, 55);
        Zombie unreadable = zombie(15, 6);
        when(target.getNearbyEntities(64, 64, 64)).thenReturn(Arrays.<Entity>asList(unreadable, visible, hidden, zombie, untracked));
        when(target.canSee(visible)).thenReturn(true);
        when(target.canSee(hidden)).thenReturn(false);
        RecordingSeed seed = mock(RecordingSeed.class);

        new NearbyEntitySeeder(new FixedStates(unreadable), LOGGER).seed(seed, target, 64);

        verify(seed).player(eq(11), eq(ALEX.getUUID()), eq("Alex"), eq(ALEX), eq(1), eq(1.0), eq(64.0), eq(2.0),
                anyFloat(), anyFloat(), anyList(), anyMap());
        verify(seed, never()).player(eq(12), any(UUID.class), anyString(), any(), anyInt(), anyDouble(), anyDouble(), anyDouble(),
                anyFloat(), anyFloat(), anyList(), anyMap());
        verify(seed).entity(eq(13), eq(ZOMBIE), any(UUID.class), eq(5.0), eq(64.0), eq(5.0), anyFloat(), anyFloat(),
                anyList(), anyMap());
        verify(seed, never()).entity(eq(14), any(), any(UUID.class), anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat(),
                anyList(), anyMap());
        verify(seed, never()).entity(eq(15), any(), any(UUID.class), anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat(),
                anyList(), anyMap());
        verify(target, never()).hidePlayer(any(Player.class));
        verify(target, never()).showPlayer(any(Player.class));
    }

    private static Zombie zombie(int entityId, double x) {
        Zombie zombie = mock(Zombie.class);
        when(zombie.getEntityId()).thenReturn(entityId);
        when(zombie.getUniqueId()).thenReturn(UUID.randomUUID());
        when(zombie.getLocation()).thenReturn(new Location(null, x, 64, x));
        return zombie;
    }

    private static Player player(UUID uuid, String name, int entityId) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn(name);
        when(player.getEntityId()).thenReturn(entityId);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getLocation()).thenReturn(new Location(null, 1, 64, 2));
        return player;
    }

    private static final class FixedStates implements EntityStates {
        private final Entity unreadable;

        private FixedStates(Entity unreadable) {
            this.unreadable = unreadable;
        }

        @Override
        public int trackingRange(Entity entity) {
            if (entity == unreadable) throw new IllegalStateException("unknown entity type");
            return 48;
        }

        @Override
        public int viewDistanceBlocks(Player viewer) {
            return 160;
        }

        @Override
        public @Nullable UserProfile profile(Player player) {
            return player.getUniqueId().equals(ALEX.getUUID()) ? ALEX : null;
        }

        @Override
        public @Nullable EntityType type(Entity entity) {
            return entity instanceof Zombie ? ZOMBIE : null;
        }

        @Override
        public List<EntityData<?>> metadata(Entity entity) {
            return Collections.emptyList();
        }

        @Override
        public Map<EquipmentSlot, ItemStack> equipment(Entity entity) {
            return Collections.emptyMap();
        }
    }
}
