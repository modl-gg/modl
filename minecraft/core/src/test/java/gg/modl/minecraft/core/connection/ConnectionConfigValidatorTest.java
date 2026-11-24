package gg.modl.minecraft.core.connection;

import gg.modl.minecraft.core.boot.PlatformType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionConfigValidatorTest {
    @Test
    void behindProxyIsRefusedOnEveryProxy() {
        for (PlatformType proxy : new PlatformType[]{PlatformType.VELOCITY, PlatformType.BUNGEECORD}) {
            ConnectionConfigException refused = assertThrows(ConnectionConfigException.class,
                    () -> ConnectionConfigValidator.validate(behindProxy("https://modl.example.com", "msk_key"), proxy));

            assertEquals("connection.yml: mode: behind-proxy is only for Paper servers behind a proxy. "
                    + "Use mode: embedded or mode: remote on a proxy.", refused.getMessage());
        }
    }

    @Test
    void behindProxyOnPaperAcceptsTheRemoteSettings() {
        assertDoesNotThrow(() -> ConnectionConfigValidator.validate(
                behindProxy("https://modl.example.com", "server_key"), PlatformType.SPIGOT));
    }

    @Test
    void behindProxyWithoutAKeyIsAcceptedSoTheServerCanWaitForOne() {
        assertDoesNotThrow(() -> ConnectionConfigValidator.validate(behindProxy("https://modl.example.com", " "),
                PlatformType.SPIGOT));
    }

    @Test
    void aTunnelAgentOnThisMachineCanBeTheOnlyTrustedProxy() {
        assertDoesNotThrow(() -> ConnectionConfigValidator.validate(ConnectionConfig.builder().mode(ConnectionMode.EMBEDDED)
                .embedded(EmbeddedSettings.builder().trustedProxy("loopback").build()).build(), PlatformType.SPIGOT));
        assertTrue(EmbeddedSettings.builder().trustedProxy(" Loopback ").build().trustsOnlyLoopbackProxies());
    }

    @Test
    void anUnknownTrustedProxyIsRefused() {
        ConnectionConfigException refused = assertThrows(ConnectionConfigException.class,
                () -> ConnectionConfigValidator.validate(ConnectionConfig.builder().mode(ConnectionMode.EMBEDDED)
                        .embedded(EmbeddedSettings.builder().trustedProxy("anyone").build()).build(), PlatformType.SPIGOT));

        assertTrue(refused.getMessage().contains("embedded.trusted-proxy must be private or loopback"), refused.getMessage());
    }

    @Test
    void remoteModeStillNeedsItsKey() {
        assertThrows(ConnectionConfigException.class, () -> ConnectionConfigValidator.validate(ConnectionConfig.builder()
                .mode(ConnectionMode.REMOTE).remote(RemoteSettings.builder().url("https://modl.example.com").apiKey(" ").build())
                .build(), PlatformType.SPIGOT));
    }

    @Test
    void behindProxyWithAMangledKeyNamesWhereServerKeysAreCreated() {
        ConnectionConfigException refused = assertThrows(ConnectionConfigException.class,
                () -> ConnectionConfigValidator.validate(behindProxy("https://modl.example.com", "key with space"),
                        PlatformType.SPIGOT));

        assertTrue(refused.getMessage().endsWith("Copy the key from Panel > Settings > General > Minecraft servers > Add server again."),
                refused.getMessage());
    }

    @Test
    void behindProxyUsesTheRemoteUrlRules() {
        ConnectionConfigException refused = assertThrows(ConnectionConfigException.class,
                () -> ConnectionConfigValidator.validate(behindProxy("ftp://modl.example.com", "server_key"),
                        PlatformType.SPIGOT));

        assertTrue(refused.getMessage().startsWith("connection.yml: remote.url 'ftp://modl.example.com' must start with"),
                refused.getMessage());
    }

    @Test
    void remoteModeKeepsTheInstanceKeyRemedy() {
        ConnectionConfigException refused = assertThrows(ConnectionConfigException.class,
                () -> ConnectionConfigValidator.validate(ConnectionConfig.builder().mode(ConnectionMode.REMOTE)
                        .remote(RemoteSettings.builder().url("https://modl.example.com").apiKey("").build()).build(),
                        PlatformType.VELOCITY));

        assertEquals("connection.yml: remote.api-key is empty. Copy the API key from Panel > Settings > General > Minecraft servers into it.",
                refused.getMessage());
    }

    @Test
    void behindProxyIsAMemberThatRunsNoCore() {
        assertTrue(ConnectionMode.BEHIND_PROXY.isMember());
        assertEquals(ConnectionMode.BEHIND_PROXY, ConnectionMode.fromYaml(" Behind-Proxy "));
        assertFalse(ConnectionMode.BEHIND_PROXY.runsCore());
        assertNull(ConnectionMode.fromYaml("bridge"));
        assertEquals("embedded, remote, behind-proxy", ConnectionMode.allowedValues());
    }

    private static ConnectionConfig behindProxy(String url, String apiKey) {
        return ConnectionConfig.builder()
                .mode(ConnectionMode.BEHIND_PROXY)
                .remote(RemoteSettings.builder().url(url).apiKey(apiKey).build())
                .build();
    }
}
