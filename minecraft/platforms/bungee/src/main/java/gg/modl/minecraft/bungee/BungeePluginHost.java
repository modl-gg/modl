package gg.modl.minecraft.bungee;

import gg.modl.minecraft.core.boot.LoginGate;
import gg.modl.minecraft.core.boot.PlatformType;
import gg.modl.minecraft.core.boot.PluginHost;
import gg.modl.minecraft.core.boot.StartupFailure;
import gg.modl.minecraft.core.util.PluginLogger;
import net.md_5.bungee.api.config.ListenerInfo;
import net.md_5.bungee.api.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

final class BungeePluginHost implements PluginHost {
    private final Plugin plugin;
    private final PluginLogger logger;
    private @Nullable BungeeLoginGateListener loginGateListener;

    BungeePluginHost(Plugin plugin, PluginLogger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    @Override
    public PlatformType platformType() {
        return PlatformType.BUNGEECORD;
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
        loginGateListener = new BungeeLoginGateListener(gate);
        plugin.getProxy().getPluginManager().registerListener(plugin, loginGateListener);
    }

    @Override
    public void unregisterLoginGate() {
        if (loginGateListener != null) plugin.getProxy().getPluginManager().unregisterListener(loginGateListener);
        loginGateListener = null;
    }

    @Override
    public void runOnMainThread(Runnable task) {
        task.run();
    }

    @Override
    public Optional<StartupFailure> unmetRequirement() {
        return Optional.empty();
    }

    @Override
    public Set<Integer> gamePorts() {
        Set<Integer> ports = new HashSet<>();
        for (ListenerInfo listener : plugin.getProxy().getConfig().getListeners()) {
            SocketAddress address = listener.getSocketAddress();
            if (address instanceof InetSocketAddress) ports.add(((InetSocketAddress) address).getPort());
        }
        return ports;
    }
}
