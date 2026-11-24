package gg.modl.minecraft.spigot.boot;

import gg.modl.minecraft.core.boot.LoginGate;
import gg.modl.minecraft.core.boot.PlatformType;
import gg.modl.minecraft.core.boot.PluginHost;
import gg.modl.minecraft.core.boot.StartupFailure;
import gg.modl.minecraft.core.connection.RconPorts;
import gg.modl.minecraft.core.packet.PacketEventsRequirement;
import gg.modl.minecraft.core.packet.PacketInterceptionPolicy;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.spigot.features.folia.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

public final class SpigotPluginHost implements PluginHost {
    private final JavaPlugin plugin;
    private final PluginLogger logger;
    private @Nullable SpigotLoginGateListener loginGateListener;

    public SpigotPluginHost(JavaPlugin plugin, PluginLogger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    @Override
    public PlatformType platformType() {
        return PlatformType.SPIGOT;
    }

    @Override
    public Path dataFolder() {
        return plugin.getDataFolder().toPath();
    }

    @Override
    public PluginLogger logger() {
        return logger;
    }

    @Override
    public void registerLoginGate(LoginGate gate) {
        loginGateListener = new SpigotLoginGateListener(gate);
        plugin.getServer().getPluginManager().registerEvents(loginGateListener, plugin);
    }

    @Override
    public void unregisterLoginGate() {
        SpigotLoginGateListener registered = loginGateListener;
        loginGateListener = null;
        if (registered != null) HandlerList.unregisterAll(registered);
    }

    @Override
    public void runOnMainThread(Runnable task) {
        if (FoliaScheduler.isFolia()) new FoliaScheduler(plugin).runGlobal(task);
        else Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public Optional<StartupFailure> unmetRequirement() {
        return PacketEventsRequirement.unmetBy(installedPacketEventsVersion());
    }

    @Override
    public Set<Integer> gamePorts() {
        return Collections.singleton(plugin.getServer().getPort());
    }

    @Override
    public Set<Integer> rconPorts() {
        return RconPorts.fromServerProperties(Paths.get(RconPorts.SERVER_PROPERTIES));
    }

    private @Nullable String installedPacketEventsVersion() {
        Plugin packetEvents = plugin.getServer().getPluginManager().getPlugin(PacketInterceptionPolicy.PACKETEVENTS_PLUGIN_ID);
        return packetEvents == null ? null : packetEvents.getDescription().getVersion();
    }
}
