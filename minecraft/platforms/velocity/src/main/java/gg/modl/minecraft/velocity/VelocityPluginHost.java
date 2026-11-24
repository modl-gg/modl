package gg.modl.minecraft.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import gg.modl.minecraft.core.boot.LoginGate;
import gg.modl.minecraft.core.boot.PlatformType;
import gg.modl.minecraft.core.boot.PluginHost;
import gg.modl.minecraft.core.boot.StartupFailure;
import gg.modl.minecraft.core.util.PluginLogger;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

final class VelocityPluginHost implements PluginHost {
    private final Object plugin;
    private final ProxyServer server;
    private final Path dataFolder;
    private final PluginLogger logger;
    private @Nullable VelocityLoginGateListener loginGateListener;

    VelocityPluginHost(Object plugin, ProxyServer server, Path dataFolder, PluginLogger logger) {
        this.plugin = plugin;
        this.server = server;
        this.dataFolder = dataFolder;
        this.logger = logger;
    }

    @Override
    public PlatformType platformType() {
        return PlatformType.VELOCITY;
    }

    @Override
    public Path dataFolder() {
        return dataFolder;
    }

    @Override
    public PluginLogger logger() {
        return logger;
    }

    @Override
    public void registerLoginGate(LoginGate gate) {
        loginGateListener = new VelocityLoginGateListener(gate);
        server.getEventManager().register(plugin, loginGateListener);
    }

    @Override
    public void unregisterLoginGate() {
        if (loginGateListener != null) server.getEventManager().unregisterListener(plugin, loginGateListener);
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
        return Collections.singleton(server.getBoundAddress().getPort());
    }
}
