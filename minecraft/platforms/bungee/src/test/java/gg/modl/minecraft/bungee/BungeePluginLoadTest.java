package gg.modl.minecraft.bungee;

import net.md_5.bungee.api.ProxyServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

class BungeePluginLoadTest {
    @TempDir
    File dataFolder;

    @Test
    void loadingLeavesTheProxyAloneBecauseBungeeCordReadsItsConfigurationAfterLoadingPlugins() {
        ProxyServer proxyBeforeItsConfigIsRead = mock(ProxyServer.class, invocation -> {
            throw new AssertionError("onLoad used the proxy before BungeeCord read config.yml: " + invocation.getMethod());
        });
        Logger logger = Logger.getLogger("modl-bungee-load-test");
        logger.setUseParentHandlers(false);
        WarningRecorder warnings = new WarningRecorder();
        logger.addHandler(warnings);
        BungeePlugin plugin = mock(BungeePlugin.class, withSettings().defaultAnswer(invocation -> invocation.callRealMethod()));
        doReturn(proxyBeforeItsConfigIsRead).when(plugin).getProxy();
        doReturn(logger).when(plugin).getLogger();
        doReturn(dataFolder).when(plugin).getDataFolder();

        assertDoesNotThrow(plugin::onLoad);

        assertEquals(Collections.<String>emptyList(), warnings.messages);
        assertFalse(new File(dataFolder, "lib").exists());
        assertFalse(new File(dataFolder, "connection.yml").exists());
    }

    private static final class WarningRecorder extends Handler {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) messages.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
