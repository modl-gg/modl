package gg.modl.minecraft.core.connection;

import gg.modl.minecraft.core.boot.PlatformType;
import gg.modl.minecraft.core.config.yaml.CoreManagedConfigs;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import gg.modl.minecraft.core.support.TempConfigs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionConfigLoaderTest {
    @TempDir
    Path dataFolder;

    private final RecordingPluginLogger logger = new RecordingPluginLogger();

    @Test
    void firstStartWritesThePackagedEmbeddedDefaults() throws IOException {
        ConnectionConfig config = loader(PlatformType.SPIGOT, ProxyForwarding.NONE).load();

        assertEquals(ConnectionMode.EMBEDDED, config.getMode());
        assertEquals(EmbeddedSettings.DEFAULT_PORT, config.getEmbedded().getPort());
        assertEquals("0.0.0.0", config.getEmbedded().getBindAddress());
        assertEquals(SmtpSettings.DEFAULT_PORT, config.getEmbedded().getSmtp().getPort());
        assertFalse(read(ConnectionConfig.FILE_NAME).contains("bridge"));
        assertEquals(CoreManagedConfigs.CONNECTION.readPackagedDefault(), read(ConnectionConfig.FILE_NAME));
    }

    @Test
    void theEmbeddedAiConnectionIsRead() throws IOException {
        TempConfigs.write(dataFolder.resolve(ConnectionConfig.FILE_NAME),
                "mode: embedded", "embedded:", "  ai:", "    provider: \"anthropic\"", "    api-key: \"sk-ant-secret\"",
                "    model: \"claude-model\"", "    base-url: \"\"");

        AiSettings ai = loader(PlatformType.SPIGOT, ProxyForwarding.NONE).load().getEmbedded().getAi();

        assertEquals("anthropic", ai.getProvider());
        assertEquals("sk-ant-secret", ai.getApiKey());
        assertEquals("claude-model", ai.getModel());
        assertEquals("", ai.getBaseUrl());
        assertFalse(ai.toString().contains("sk-ant-secret"));
    }

    @Test
    void firstStartBehindAProxyWritesBehindProxyModeAndStartsWithoutAKey() throws IOException {
        ConnectionConfig config = loader(PlatformType.SPIGOT, ProxyForwarding.BUNGEECORD).load();

        assertEquals(ConnectionMode.BEHIND_PROXY, config.getMode());
        assertFalse(config.getRemote().hasApiKey());
        assertTrue(read(ConnectionConfig.FILE_NAME).contains("mode: behind-proxy"));
        assertTrue(logger.infos().stream().anyMatch(line -> line.contains("spigot.yml settings.bungeecord")
                && line.endsWith("was created with mode: behind-proxy.")));
    }

    @Test
    void legacyBridgeOnlyBootConfigBecomesBehindProxyWithoutItsOldKey() throws IOException {
        TempConfigs.write(dataFolder.resolve("boot.yml"),
                "mode: bridge-only", "api-key: \"modl_legacy\"", "proxy-host: \"10.0.0.5\"", "proxy-port: 25591");

        ConnectionConfig config = loader(PlatformType.SPIGOT, ProxyForwarding.NONE).load();

        assertEquals(ConnectionMode.BEHIND_PROXY, config.getMode());
        assertEquals("", config.getRemote().getApiKey());
        assertFalse(read(ConnectionConfig.FILE_NAME).contains("modl_legacy"));
        assertFalse(Files.exists(dataFolder.resolve("boot.yml")));
        assertTrue(Files.exists(dataFolder.resolve("boot.yml.old")));
        assertTrue(logger.warnings().stream().anyMatch(line -> line.contains("fresh instance")));
        assertTrue(logger.warnings().stream().anyMatch(line -> line.contains("remote.api-key")
                && line.contains(PanelLocations.ADD_SERVER)));
    }

    @Test
    void legacyStandaloneBootConfigBecomesEmbedded() throws IOException {
        TempConfigs.write(dataFolder.resolve("boot.yml"), "mode: standalone", "api-key: \"modl_legacy\"");

        ConnectionConfig config = loader(PlatformType.SPIGOT, ProxyForwarding.BUNGEECORD).load();

        assertEquals(ConnectionMode.EMBEDDED, config.getMode());
        assertEquals("", config.getRemote().getApiKey());
        assertTrue(Files.exists(dataFolder.resolve("boot.yml.old")));
    }

    @Test
    void legacyProxyBootConfigBecomesEmbeddedWithoutABridgePort() throws IOException {
        TempConfigs.write(dataFolder.resolve("boot.yml"), "mode: proxy", "api-key: \"modl_legacy\"", "bridge-port: 25600");

        ConnectionConfig config = loader(PlatformType.VELOCITY, ProxyForwarding.NONE).load();

        assertEquals(ConnectionMode.EMBEDDED, config.getMode());
        assertFalse(read(ConnectionConfig.FILE_NAME).contains("25600"));
    }

    @Test
    void newKeysAreMergedIntoAnExistingFileWithoutTouchingValues() throws IOException {
        TempConfigs.write(dataFolder.resolve(ConnectionConfig.FILE_NAME),
                "mode: remote", "remote:", "  url: \"https://modl.example.com\"", "  api-key: \"modl_remote\"");

        ConnectionConfig config = loader(PlatformType.VELOCITY, ProxyForwarding.NONE).load();

        assertEquals(ConnectionMode.REMOTE, config.getMode());
        assertEquals("https://modl.example.com", config.getRemote().getUrl());
        assertEquals("modl_remote", config.getRemote().getApiKey());
        assertTrue(read(ConnectionConfig.FILE_NAME).contains("trusted-proxy:"));
    }

    @Test
    void behindProxyModeIsRejectedOnProxies() throws IOException {
        TempConfigs.write(dataFolder.resolve(ConnectionConfig.FILE_NAME),
                "mode: behind-proxy", "remote:", "  api-key: \"msk_key\"");

        ConnectionConfigException error = assertThrows(ConnectionConfigException.class,
                () -> loader(PlatformType.BUNGEECORD, ProxyForwarding.NONE).load());

        assertTrue(error.getMessage().contains("only for Paper servers"));
    }

    @Test
    void unknownModesAreRejectedWithTheAllowedValues() throws IOException {
        TempConfigs.write(dataFolder.resolve(ConnectionConfig.FILE_NAME), "mode: hybrid");

        ConnectionConfigException error = assertThrows(ConnectionConfigException.class,
                () -> loader(PlatformType.SPIGOT, ProxyForwarding.NONE).load());

        assertTrue(error.getMessage().contains("embedded, remote, behind-proxy"));
        assertTrue(error.getMessage().contains("hybrid"));
    }

    @Test
    void theRemovedBridgeModeIsRejectedLikeAnyUnknownMode() throws IOException {
        TempConfigs.write(dataFolder.resolve(ConnectionConfig.FILE_NAME),
                "mode: bridge", "bridge:", "  proxy-host: \"10.0.0.5\"", "  api-key: \"modl_key\"");

        ConnectionConfigException error = assertThrows(ConnectionConfigException.class,
                () -> loader(PlatformType.SPIGOT, ProxyForwarding.NONE).load());

        assertTrue(error.getMessage().contains("embedded, remote, behind-proxy"));
        assertTrue(error.getMessage().contains("'bridge'"));
    }

    @Test
    void remoteModeRequiresAnApiKeyAndAnHttpUrl() throws IOException {
        TempConfigs.write(dataFolder.resolve(ConnectionConfig.FILE_NAME),
                "mode: remote", "remote:", "  url: \"https://modl.example.com\"", "  api-key: \"\"");
        assertTrue(assertThrows(ConnectionConfigException.class,
                () -> loader(PlatformType.SPIGOT, ProxyForwarding.NONE).load()).getMessage().contains("remote.api-key"));

        TempConfigs.write(dataFolder.resolve(ConnectionConfig.FILE_NAME),
                "mode: remote", "remote:", "  url: \"ftp://modl.example.com\"", "  api-key: \"modl_key\"");
        assertTrue(assertThrows(ConnectionConfigException.class,
                () -> loader(PlatformType.SPIGOT, ProxyForwarding.NONE).load()).getMessage().contains("remote.url"));
    }

    @Test
    void invalidNumbersAndPublicUrlsAreRejected() throws IOException {
        TempConfigs.write(dataFolder.resolve(ConnectionConfig.FILE_NAME), "mode: embedded", "embedded:", "  port: \"abc\"");
        assertTrue(assertThrows(ConnectionConfigException.class,
                () -> loader(PlatformType.SPIGOT, ProxyForwarding.NONE).load()).getMessage().contains("embedded.port"));

        TempConfigs.write(dataFolder.resolve(ConnectionConfig.FILE_NAME), "mode: embedded", "embedded:",
                "  public-url: \"https://panel.example.com/path\"");
        assertTrue(assertThrows(ConnectionConfigException.class,
                () -> loader(PlatformType.SPIGOT, ProxyForwarding.NONE).load()).getMessage().contains("embedded.public-url"));
    }

    @Test
    void hostNamesWithUnderscoresAreAcceptedEverywhere() throws IOException {
        TempConfigs.write(dataFolder.resolve(ConnectionConfig.FILE_NAME),
                "mode: behind-proxy", "remote:", "  url: \"http://velocity_proxy:8080\"", "  api-key: \"msk_key\"");
        assertEquals("http://velocity_proxy:8080", loader(PlatformType.SPIGOT, ProxyForwarding.NONE).load().getRemote().getUrl());

        TempConfigs.write(dataFolder.resolve(ConnectionConfig.FILE_NAME),
                "mode: remote", "remote:", "  url: \"http://modl_server:8080\"", "  api-key: \"modl_key\"");
        assertEquals("http://modl_server:8080", loader(PlatformType.SPIGOT, ProxyForwarding.NONE).load().getRemote().getUrl());
    }

    @Test
    void invalidHostsAndPortsAreRejectedWithTheKeyThatHoldsThem() throws IOException {
        TempConfigs.write(dataFolder.resolve(ConnectionConfig.FILE_NAME),
                "mode: embedded", "embedded:", "  bind-address: \"bad host\"");
        assertTrue(assertThrows(ConnectionConfigException.class,
                () -> loader(PlatformType.SPIGOT, ProxyForwarding.NONE).load()).getMessage().contains("embedded.bind-address"));

        TempConfigs.write(dataFolder.resolve(ConnectionConfig.FILE_NAME), "mode: embedded", "embedded:", "  port: 70000");
        assertTrue(assertThrows(ConnectionConfigException.class,
                () -> loader(PlatformType.VELOCITY, ProxyForwarding.NONE).load()).getMessage().contains("embedded.port"));

        TempConfigs.write(dataFolder.resolve(ConnectionConfig.FILE_NAME),
                "mode: remote", "remote:", "  url: \"http://modl.example.com:0\"", "  api-key: \"modl_key\"");
        assertTrue(assertThrows(ConnectionConfigException.class,
                () -> loader(PlatformType.SPIGOT, ProxyForwarding.NONE).load()).getMessage().contains("not between 1 and 65535"));
    }

    private ConnectionConfigLoader loader(PlatformType platform, ProxyForwarding forwarding) {
        return new ConnectionConfigLoader(dataFolder, platform, forwarding, logger);
    }

    private String read(String fileName) throws IOException {
        return new String(Files.readAllBytes(dataFolder.resolve(fileName)), StandardCharsets.UTF_8);
    }
}
