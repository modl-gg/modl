package gg.modl.minecraft.spigot.boot;

import gg.modl.minecraft.core.connection.ProxyForwarding;
import gg.modl.minecraft.core.connection.ProxyForwardingDetector;
import gg.modl.minecraft.core.util.PluginLogger;
import org.bukkit.Server;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.nio.file.Path;

public final class SpigotProxyForwarding {
    private static final String PAPER_SPIGOT_CONFIG_METHOD = "getSpigotConfig";
    private static final String SPIGOT_CONFIG_METHOD = "getConfig";
    private static final String PAPER_CONFIG_METHOD = "getPaperConfig";
    private static final String[] VELOCITY_FORWARDING_KEYS = {"proxies.velocity.enabled", "settings.velocity-support.enabled"};
    private static final String BUNGEECORD_FORWARDING_KEY = "settings.bungeecord";

    private SpigotProxyForwarding() {
    }

    public static ProxyForwarding detect(Server server, Path serverRoot, PluginLogger logger) {
        ProxyForwarding fromServer = fromServerConfiguration(server, logger);
        return fromServer != null ? fromServer : new ProxyForwardingDetector(serverRoot).detect();
    }

    private static @Nullable ProxyForwarding fromServerConfiguration(Server server, PluginLogger logger) {
        Server.Spigot spigot;
        try {
            spigot = server.spigot();
        } catch (RuntimeException | LinkageError notSpigot) {
            return null;
        }
        return fromServerApi(spigot, Server.Spigot.class, logger);
    }

    static @Nullable ProxyForwarding fromServerApi(Object spigot, Class<?> spigotApiType, PluginLogger logger) {
        ConfigurationSection spigotConfig = spigotConfig(spigot, spigotApiType, logger);
        if (spigotConfig == null) return null;
        Boolean velocity = velocityForwardingEnabled(spigot, spigotApiType, logger);
        if (velocity == null) return null;
        if (velocity) return ProxyForwarding.VELOCITY;
        return spigotConfig.getBoolean(BUNGEECORD_FORWARDING_KEY) ? ProxyForwarding.BUNGEECORD : ProxyForwarding.NONE;
    }

    static @Nullable Boolean velocityForwardingEnabled(Object spigot, Class<?> spigotApiType, PluginLogger logger) {
        Method paperConfigAccessor = accessor(spigotApiType, PAPER_CONFIG_METHOD);
        if (paperConfigAccessor == null) return Boolean.FALSE;
        ConfigurationSection paperConfig = read(paperConfigAccessor, spigot, logger);
        if (paperConfig == null) return null;
        for (String key : VELOCITY_FORWARDING_KEYS) {
            if (paperConfig.getBoolean(key)) return Boolean.TRUE;
        }
        return Boolean.FALSE;
    }

    private static @Nullable ConfigurationSection spigotConfig(Object spigot, Class<?> spigotApiType, PluginLogger logger) {
        Method paperSpigotConfigAccessor = accessor(spigotApiType, PAPER_SPIGOT_CONFIG_METHOD);
        Method accessor = paperSpigotConfigAccessor != null
                ? paperSpigotConfigAccessor
                : accessor(spigotApiType, SPIGOT_CONFIG_METHOD);
        return accessor == null ? null : read(accessor, spigot, logger);
    }

    private static @Nullable Method accessor(Class<?> spigotApiType, String name) {
        try {
            return spigotApiType.getMethod(name);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static @Nullable ConfigurationSection read(Method accessor, Object spigot, PluginLogger logger) {
        Object config;
        try {
            config = accessor.invoke(spigot);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) {
            logger.debug("Could not read " + accessor.getName() + " through the server API, reading the config files "
                    + "instead: " + unreadable);
            return null;
        }
        return config instanceof ConfigurationSection ? (ConfigurationSection) config : null;
    }
}
