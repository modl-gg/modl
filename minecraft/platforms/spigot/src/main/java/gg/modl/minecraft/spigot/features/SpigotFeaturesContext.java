package gg.modl.minecraft.spigot.features;

import gg.modl.minecraft.features.FeaturePlayerProvider;
import gg.modl.minecraft.features.FeaturesPluginContext;
import gg.modl.minecraft.features.FeatureScheduler;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpigotFeaturesContext extends SpigotFeaturesResources implements FeaturesPluginContext {
    private static final Pattern RELEASE_VERSION = Pattern.compile("^\\d+(?:\\.\\d+)*");

    @Getter private final FeatureScheduler scheduler;
    @Getter private final FeaturePlayerProvider playerProvider;

    public SpigotFeaturesContext(JavaPlugin plugin) {
        super(plugin);
        this.scheduler = new SpigotFeatureScheduler(plugin);
        this.playerProvider = new SpigotPlayerProvider();
    }

    @Override
    public String getMinecraftVersion() {
        return releaseVersion(Bukkit.getBukkitVersion());
    }

    static String releaseVersion(String bukkitVersion) {
        Matcher matcher = RELEASE_VERSION.matcher(bukkitVersion);
        return matcher.find() ? matcher.group() : bukkitVersion;
    }
}
