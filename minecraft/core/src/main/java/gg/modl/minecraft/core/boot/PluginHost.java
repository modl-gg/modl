package gg.modl.minecraft.core.boot;

import gg.modl.minecraft.core.util.PluginLogger;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

public interface PluginHost {
    PlatformType platformType();

    Path dataFolder();

    PluginLogger logger();

    void registerLoginGate(LoginGate gate);

    void unregisterLoginGate();

    void runOnMainThread(Runnable task);

    Optional<StartupFailure> unmetRequirement();

    Set<Integer> gamePorts();

    default Set<Integer> rconPorts() {
        return Collections.emptySet();
    }
}
