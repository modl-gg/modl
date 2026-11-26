package gg.modl.minecraft.spigot.features;

import gg.modl.minecraft.core.util.PublicMethods;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Ambient;
import org.bukkit.entity.Creature;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class SpigotTrackingRanges {
    private static final String WORLD_SETTINGS = "world-settings";
    private static final String DEFAULT_WORLD = "default";
    private static final String TRACKING_RANGE = "entity-tracking-range";

    private static final ClassValue<List<Method>> PLAYER_VIEW_DISTANCES = new ClassValue<List<Method>>() {
        @Override
        protected List<Method> computeValue(Class<?> type) {
            List<Method> methods = new ArrayList<>(PublicMethods.withoutParameters(type, "getClientViewDistance"));
            methods.addAll(PublicMethods.withoutParameters(type, "getViewDistance"));
            return methods;
        }
    };
    private static final ClassValue<List<Method>> WORLD_VIEW_DISTANCES = new ClassValue<List<Method>>() {
        @Override
        protected List<Method> computeValue(Class<?> type) {
            return PublicMethods.withoutParameters(type, "getViewDistance");
        }
    };

    private final @Nullable ConfigurationSection worlds;
    private final int viewDistanceChunks;
    private final Map<String, Map<Category, Integer>> byWorld = new ConcurrentHashMap<>();

    SpigotTrackingRanges(@Nullable ConfigurationSection worlds, int viewDistanceChunks) {
        this.worlds = worlds;
        this.viewDistanceChunks = Math.max(1, viewDistanceChunks);
    }

    static SpigotTrackingRanges ofServer() {
        return new SpigotTrackingRanges(spigotWorldSettings(), Bukkit.getViewDistance());
    }

    private static @Nullable ConfigurationSection spigotWorldSettings() {
        try {
            return Bukkit.spigot().getConfig().getConfigurationSection(WORLD_SETTINGS);
        } catch (RuntimeException | LinkageError notSpigot) {
            return null;
        }
    }

    int rangeOf(Entity entity) {
        Map<Category, Integer> ranges = byWorld.computeIfAbsent(entity.getWorld().getName(), this::rangesOf);
        Integer range = ranges.get(Category.of(entity));
        return range == null ? Category.OTHER.fallback : range;
    }

    int viewDistanceBlocks(Player viewer) {
        int chunks = viewDistanceChunks;
        chunks = smallest(chunks, viewer, PLAYER_VIEW_DISTANCES.get(viewer.getClass()));
        World world = viewer.getWorld();
        if (world != null) chunks = smallest(chunks, world, WORLD_VIEW_DISTANCES.get(world.getClass()));
        return chunks * 16;
    }

    private static int smallest(int chunks, Object owner, List<Method> probes) {
        int smallest = chunks;
        for (Method probe : probes) smallest = Math.min(smallest, read(probe, owner, smallest));
        return smallest;
    }

    private static int read(Method probe, Object owner, int fallback) {
        try {
            Object value = probe.invoke(owner);
            return value instanceof Integer && (Integer) value > 0 ? (Integer) value : fallback;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return fallback;
        }
    }

    private Map<Category, Integer> rangesOf(String world) {
        Map<Category, Integer> ranges = new EnumMap<>(Category.class);
        for (Category category : Category.values()) ranges.put(category, configured(world, category));
        return ranges;
    }

    private int configured(String world, Category category) {
        ConfigurationSection settings = worlds;
        if (settings == null) return category.fallback;
        String key = TRACKING_RANGE + "." + category.key;
        return settings.getInt(world + "." + key, settings.getInt(DEFAULT_WORLD + "." + key, category.fallback));
    }

    private enum Category {
        PLAYERS("players", 48),
        MONSTERS("monsters", 48),
        ANIMALS("animals", 48),
        MISC("misc", 32),
        OTHER("other", 64);

        private final String key;
        private final int fallback;

        Category(String key, int fallback) {
            this.key = key;
            this.fallback = fallback;
        }

        static Category of(Entity entity) {
            if (entity instanceof Player) return PLAYERS;
            if (entity instanceof Monster || entity instanceof Slime || entity instanceof Ghast) return MONSTERS;
            if (entity instanceof Creature || entity instanceof Ambient) return ANIMALS;
            if (entity instanceof Item || entity instanceof ExperienceOrb || entity instanceof ItemFrame || entity instanceof Painting) return MISC;
            return OTHER;
        }
    }
}
