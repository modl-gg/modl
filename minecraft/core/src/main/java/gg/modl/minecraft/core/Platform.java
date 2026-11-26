package gg.modl.minecraft.core;

import gg.modl.minecraft.core.network.ProxyBackend;
import gg.modl.minecraft.core.util.PluginLogger;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public interface Platform extends PlatformMessaging, PlatformPlayers, PlatformCommands, Scheduler {
    PluginLogger getLogger();
    void log(String msg);
    String getServerVersion();
    String getPlatformType();
    String getServerName();
    File getDataFolder();
    boolean isPluginEnabled(String name);
    boolean isOnlineModeAuthenticated(UUID player);
    boolean assignsOfflineUuids();

    default String getPlayerServer(UUID uuid) { return getServerName(); }
    default void connectToServer(UUID player, String serverName) {}
    default List<ProxyBackend> proxyBackends() { return Collections.emptyList(); }
    default boolean hostsNetworkMembers() { return false; }
    default boolean hostsServerFeatures() { return false; }
}
