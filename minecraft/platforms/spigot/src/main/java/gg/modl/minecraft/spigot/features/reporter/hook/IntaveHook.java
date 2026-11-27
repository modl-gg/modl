package gg.modl.minecraft.spigot.features.reporter.hook;

import de.jpx3.intave.access.check.event.IntaveViolationEvent;
import gg.modl.minecraft.features.config.ServerFeaturesConfig;
import gg.modl.minecraft.features.reporter.AutoReporter;
import gg.modl.minecraft.features.reporter.detection.DetectionSource;
import gg.modl.minecraft.features.reporter.detection.ViolationTracker;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public class IntaveHook extends AbstractAnticheatHook<IntaveViolationEvent> implements Listener {
    private static final String HOOK_NAME = "Intave";

    private final JavaPlugin plugin;

    public IntaveHook(JavaPlugin plugin, ServerFeaturesConfig config, ViolationTracker violationTracker, AutoReporter autoReporter) {
        super(plugin, config, violationTracker, autoReporter, HOOK_NAME, HOOK_NAME, DetectionSource.INTAVE);

        this.plugin = plugin;
    }

    @Override
    protected AnticheatFlag extractFlag(IntaveViolationEvent event) {
        return new AnticheatFlag(
                event.player().getUniqueId(),
                event.player().getName(),
                event.checkName(),
                event.message()
        );
    }

    @Override
    public boolean isAvailable() {
        return Bukkit.getPluginManager().getPlugin(HOOK_NAME) != null;
    }

    @Override
    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        logHooked();
    }

    @Override
    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    @EventHandler
    public void onFlag(IntaveViolationEvent event) {
        handle(event);
    }
}
