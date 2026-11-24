package gg.modl.minecraft.bungee;

import com.alessiodp.libby.BungeeLibraryManager;
import gg.modl.minecraft.api.http.request.StartupRequest;
import gg.modl.minecraft.core.boot.PlatformType;
import gg.modl.minecraft.core.boot.PluginLifecycle;
import gg.modl.minecraft.core.boot.RuntimeLibraryLoader;
import gg.modl.minecraft.core.connection.ProxyForwarding;
import gg.modl.minecraft.core.imports.sql.LoadedDriverLibraries;
import gg.modl.minecraft.core.plugin.PluginInfo;
import gg.modl.minecraft.core.util.PluginLogger;
import net.md_5.bungee.api.plugin.Plugin;
import org.jspecify.annotations.Nullable;

public class BungeePlugin extends Plugin {

    private final LoadedDriverLibraries driverLibraries = new LoadedDriverLibraries();
    private @Nullable PluginLifecycle lifecycle;

    @Override
    public synchronized void onEnable() {
        PluginLogger pluginLogger = PluginLogger.fromJul(getLogger());
        PluginLifecycle started = new PluginLifecycle(new BungeePluginHost(this, pluginLogger), ProxyForwarding.NONE);
        lifecycle = started;
        started.load(this::loadLibraries);
        started.enable(new StartupRequest(PluginInfo.VERSION, "BUNGEECORD", getProxy().getVersion(),
                getProxy().getConfig().getPlayerLimit()), new BungeeCore(this, pluginLogger, driverLibraries));
    }

    @Override
    public synchronized void onDisable() {
        PluginLifecycle stopping = lifecycle;
        lifecycle = null;
        if (stopping != null) stopping.shutdown();
    }

    private void loadLibraries() {
        RuntimeLibraryLoader libraries =
                new RuntimeLibraryLoader(new BungeeLibraryManager(this), PlatformType.BUNGEECORD, getClass().getClassLoader());
        libraries.loadAll();
        driverLibraries.loaded(libraries);
    }
}
