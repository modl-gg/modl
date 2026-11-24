package gg.modl.minecraft.spigot.boot;

import com.github.retrooper.packetevents.PacketEvents;
import gg.modl.minecraft.core.connection.ProxyForwarding;
import gg.modl.minecraft.core.util.JavaRuntime;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.spigot.chat.ProxyChatVerdictListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;

public final class SignedVelocityBootstrap {
    static final String INTEGRATION_CLASS = "io.github._4drian3d.signedvelocity.paper.SignedVelocity";
    static final String INTEGRATION_FACTORY = "start";
    private static final String STANDALONE_PLUGIN = "SignedVelocity";
    private static final String PACKET_EVENTS_CLASS = "com.github.retrooper.packetevents.PacketEvents";

    private final JavaPlugin plugin;
    private final PluginLogger logger;
    private final ClassLoader classLoader;
    private @Nullable AutoCloseable integration;

    public SignedVelocityBootstrap(JavaPlugin plugin, PluginLogger logger) {
        this(plugin, logger, SignedVelocityBootstrap.class.getClassLoader());
    }

    SignedVelocityBootstrap(JavaPlugin plugin, PluginLogger logger, ClassLoader classLoader) {
        this.plugin = plugin;
        this.logger = logger;
        this.classLoader = classLoader;
    }

    public void init(ProxyForwarding forwarding) {
        if (plugin.getServer().getPluginManager().getPlugin(STANDALONE_PLUGIN) != null) {
            logger.info("[SignedVelocity] Using standalone SignedVelocity plugin");
            return;
        }
        SignedVelocitySupport support = new SignedVelocitySupport(
                JavaRuntime.featureVersionRequiredBy(classLoader, INTEGRATION_CLASS));
        boolean embeddedApplies = forwarding == ProxyForwarding.VELOCITY
                && support.isSupportedBy(JavaRuntime.currentFeatureVersion(), this::serverHasClass);
        if (embeddedApplies && start()) return;
        startPacketVerdicts();
    }

    private void startPacketVerdicts() {
        shutdown();
        if (!serverHasClass(PACKET_EVENTS_CLASS) || PacketEvents.getAPI() == null) {
            logger.debug("[SignedVelocity] PacketEvents is not running; proxy chat denials are not applied on this server");
            return;
        }
        integration = ProxyChatVerdictListener.start(plugin);
        logger.debug("[SignedVelocity] Applying proxy chat denials through PacketEvents");
    }

    public void shutdown() {
        AutoCloseable running = integration;
        integration = null;
        if (running == null) return;
        try {
            running.close();
        } catch (Exception e) {
            logger.warning("[SignedVelocity] Failed to shutdown", e);
        }
    }

    private boolean start() {
        shutdown();
        try {
            Method factory = Class.forName(INTEGRATION_CLASS, true, classLoader)
                    .getMethod(INTEGRATION_FACTORY, JavaPlugin.class);
            integration = AutoCloseable.class.cast(factory.invoke(null, plugin));
            logger.debug("[SignedVelocity] Embedded listeners registered");
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            logger.warning("[SignedVelocity] Failed to initialize", e);
            return false;
        }
    }

    private boolean serverHasClass(String className) {
        try {
            Class.forName(className, false, classLoader);
            return true;
        } catch (ClassNotFoundException | LinkageError missing) {
            return false;
        }
    }
}
