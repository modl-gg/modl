package gg.modl.minecraft.core;

import gg.modl.minecraft.core.util.PluginLogger;

import java.io.File;
import java.util.Collections;
import java.util.Set;
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
    default Set<String> knownServerNames() { return Collections.singleton(getServerName()); }
    default boolean hostsNetworkMembers() { return false; }
}
