package gg.modl.minecraft.spigot.features;

import gg.modl.minecraft.features.FeaturesResources;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

public class SpigotFeaturesResources implements FeaturesResources {
    private final JavaPlugin plugin;

    public SpigotFeaturesResources(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    @Override
    public Path getDataFolder() {
        return plugin.getDataFolder().toPath();
    }

    @Override
    public void saveDefaultResource(String resourcePath) {
        plugin.saveResource(resourcePath, false);
    }
}
