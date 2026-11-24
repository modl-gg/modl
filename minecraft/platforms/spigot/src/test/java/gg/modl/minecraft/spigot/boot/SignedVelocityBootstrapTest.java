package gg.modl.minecraft.spigot.boot;

import gg.modl.minecraft.core.connection.ProxyForwarding;
import gg.modl.minecraft.core.util.PluginLogger;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SignedVelocityBootstrapTest {
    private static final String PACKET_EVENTS_NOT_RUNNING =
            "[SignedVelocity] PacketEvents is not running; proxy chat denials are not applied on this server";

    private final JavaPlugin plugin = mock(JavaPlugin.class);
    private final Server server = mock(Server.class);
    private final PluginManager pluginManager = mock(PluginManager.class);
    private final RecordingLogger logger = new RecordingLogger();
    private final RecordingClassLoader classLoader = new RecordingClassLoader();
    private SignedVelocityBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);
        bootstrap = new SignedVelocityBootstrap(plugin, logger, classLoader);
    }

    @ParameterizedTest
    @EnumSource(value = ProxyForwarding.class, names = {"NONE", "BUNGEECORD"})
    void withoutVelocityModernForwardingTheProxyDenialsAreAppliedThroughPacketEvents(ProxyForwarding forwarding) {
        bootstrap.init(forwarding);

        assertTrue(logger.console.isEmpty(), logger.console.toString());
        assertEquals(Collections.singletonList(PACKET_EVENTS_NOT_RUNNING), logger.debug);
        assertFalse(classLoader.requested.contains(SignedVelocityBootstrap.INTEGRATION_CLASS));
    }

    @Test
    void aStandaloneSignedVelocityPluginTakesOver() {
        when(pluginManager.getPlugin("SignedVelocity")).thenReturn(mock(Plugin.class));

        bootstrap.init(ProxyForwarding.VELOCITY);

        assertEquals(Collections.singletonList("[SignedVelocity] Using standalone SignedVelocity plugin"), logger.console);
        assertFalse(classLoader.requested.contains(SignedVelocityBootstrap.INTEGRATION_CLASS));
    }

    @Test
    void aServerWithoutPapersSignedChatApiFallsBackToTheProxyDenialsQuietly() {
        bootstrap.init(ProxyForwarding.VELOCITY);
        bootstrap.shutdown();

        assertTrue(logger.console.isEmpty(), logger.console.toString());
        assertEquals(Collections.singletonList(PACKET_EVENTS_NOT_RUNNING), logger.debug);
        assertTrue(classLoader.requested.contains(SignedVelocitySupport.REQUIRED_SERVER_CLASSES.get(0)));
        assertFalse(classLoader.requested.contains(SignedVelocityBootstrap.INTEGRATION_CLASS));
    }

    @Test
    void shuttingDownWithoutAnIntegrationLoadsNothing() {
        bootstrap.shutdown();

        verifyNoInteractions(plugin);
        assertTrue(logger.console.isEmpty(), logger.console.toString());
        assertTrue(classLoader.requested.isEmpty(), classLoader.requested.toString());
    }

    @Test
    void theIntegrationExposesTheFactoryTheBootstrapCalls() throws ReflectiveOperationException {
        Method factory = Class.forName(SignedVelocityBootstrap.INTEGRATION_CLASS, false, classLoader)
                .getMethod(SignedVelocityBootstrap.INTEGRATION_FACTORY, JavaPlugin.class);

        assertTrue(Modifier.isStatic(factory.getModifiers()));
        assertTrue(AutoCloseable.class.isAssignableFrom(factory.getReturnType()));
    }

    private static final class RecordingLogger implements PluginLogger {
        private final List<String> console = new ArrayList<>();
        private final List<String> debug = new ArrayList<>();

        @Override
        public void info(String message) {
            console.add(message);
        }

        @Override
        public void warning(String message) {
            console.add(message);
        }

        @Override
        public void severe(String message) {
            console.add(message);
        }

        @Override
        public void debug(String message) {
            debug.add(message);
        }
    }

    private static final class RecordingClassLoader extends ClassLoader {
        private final List<String> requested = new ArrayList<>();

        private RecordingClassLoader() {
            super(SignedVelocityBootstrapTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            requested.add(name);
            return super.loadClass(name, resolve);
        }
    }
}
