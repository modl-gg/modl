package gg.modl.minecraft.spigot.features.reporter.hook;

import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.api.GrimUser;
import ac.grim.grimac.api.event.events.FlagEvent;
import ac.grim.grimac.api.plugin.BasicGrimPlugin;
import ac.grim.grimac.api.plugin.GrimPlugin;
import gg.modl.minecraft.features.config.ServerFeaturesConfig;
import gg.modl.minecraft.features.reporter.detection.DetectionSource;
import gg.modl.minecraft.features.reporter.detection.ViolationTracker;
import gg.modl.minecraft.features.reporter.AutoReporter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import static gg.modl.minecraft.core.util.Java8Collections.listOf;

public class GrimHook extends AbstractAnticheatHook<FlagEvent> {
    private static final String HOOK_NAME = "GrimAC";
    private static final String LOG_LABEL = "Grim";

    private @Nullable Runnable unsubscribe;

    public GrimHook(JavaPlugin plugin, ServerFeaturesConfig config, ViolationTracker violationTracker, AutoReporter autoReporter) {
        super(plugin, config, violationTracker, autoReporter, HOOK_NAME, LOG_LABEL, DetectionSource.GRIM);
    }

    @Override
    public boolean isAvailable() {
        return Bukkit.getPluginManager().getPlugin(HOOK_NAME) != null;
    }

    @Override
    public void register() {
        try {
            RegisteredServiceProvider<GrimAbstractAPI> registration =
                    Bukkit.getServicesManager().getRegistration(GrimAbstractAPI.class);
            if (registration == null) {
                logHookFailure(new IllegalStateException("GrimAC has not registered its API"));
                return;
            }
            GrimAbstractAPI grimApi = registration.getProvider();
            GrimPlugin grimPlugin = new BasicGrimPlugin(
                    plugin.getLogger(),
                    plugin.getDataFolder(),
                    plugin.getName(),
                    plugin.getDescription().getVersion(),
                    listOf()
            );
            grimApi.getEventBus().subscribe(grimPlugin, FlagEvent.class, this::handle);
            unsubscribe = () -> grimApi.getEventBus().unregisterAllListeners(grimPlugin);
            logHooked();
        } catch (Exception e) {
            logHookFailure(e);
        }
    }

    @Override
    public void unregister() {
        Runnable subscription = unsubscribe;
        unsubscribe = null;
        if (subscription != null) subscription.run();
    }

    @Override
    protected @Nullable AnticheatFlag extractFlag(FlagEvent event) {
        if (event.isCancelled()) {
            logCancelled(event.getCheck().getCheckName(), event.getPlayer().getName());
            return null;
        }

        GrimUser user = event.getPlayer();
        return new AnticheatFlag(user.getUniqueId(), user.getName(),
                event.getCheck().getCheckName(), event.getVerbose());
    }
}
