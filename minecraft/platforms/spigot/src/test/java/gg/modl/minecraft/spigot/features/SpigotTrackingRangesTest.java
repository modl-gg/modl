package gg.modl.minecraft.spigot.features;

import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpigotTrackingRangesTest {
    @Test
    void rangesComeFromTheWorldThenTheDefaults() {
        YamlConfiguration spigot = new YamlConfiguration();
        spigot.set("world-settings.default.entity-tracking-range.players", 40);
        spigot.set("world-settings.arena.entity-tracking-range.players", 24);
        spigot.set("world-settings.default.entity-tracking-range.misc", 200);
        SpigotTrackingRanges ranges = new SpigotTrackingRanges(spigot.getConfigurationSection("world-settings"), 10);

        assertEquals(24, ranges.rangeOf(in(mock(Player.class), "arena")));
        assertEquals(40, ranges.rangeOf(in(mock(Player.class), "world")));
        assertEquals(48, ranges.rangeOf(in(mock(Zombie.class), "world")));
        assertEquals(200, ranges.rangeOf(in(mock(Item.class), "world")));
    }

    @Test
    void theViewDistanceOfTheServerCapsWhatAPlayerTracks() {
        SpigotTrackingRanges ranges = new SpigotTrackingRanges(null, 6);

        assertEquals(96, ranges.viewDistanceBlocks(mock(Player.class)));
    }

    @Test
    void withoutSpigotSettingsTheVanillaDefaultsApply() {
        SpigotTrackingRanges ranges = new SpigotTrackingRanges(null, 10);

        assertEquals(48, ranges.rangeOf(in(mock(Player.class), "world")));
        assertEquals(32, ranges.rangeOf(in(mock(Item.class), "world")));
    }

    private static <T extends Entity> T in(T entity, String worldName) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(worldName);
        when(entity.getWorld()).thenReturn(world);
        return entity;
    }
}
