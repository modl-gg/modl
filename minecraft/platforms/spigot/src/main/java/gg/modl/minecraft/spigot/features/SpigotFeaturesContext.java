package gg.modl.minecraft.spigot.features;

import gg.modl.minecraft.features.FeaturePlayerProvider;
import gg.modl.minecraft.features.FeaturesPluginContext;
import gg.modl.minecraft.features.FeatureScheduler;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class SpigotFeaturesContext extends SpigotFeaturesResources implements FeaturesPluginContext {
    @Getter private final FeatureScheduler scheduler;
    @Getter private final FeaturePlayerProvider playerProvider;

    public SpigotFeaturesContext(JavaPlugin plugin) {
        super(plugin);
        this.scheduler = new SpigotFeatureScheduler(plugin);
        this.playerProvider = new SpigotPlayerProvider();
    }

    @Override
    public String getMinecraftVersion() {
        String bukkit = Bukkit.getBukkitVersion();
        int dash = bukkit.indexOf('-');
        return dash > 0 ? bukkit.substring(0, dash) : bukkit;
    }
}
