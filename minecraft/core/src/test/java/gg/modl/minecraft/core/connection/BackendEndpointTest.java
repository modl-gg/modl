package gg.modl.minecraft.core.connection;

import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendEndpointTest {
    @Test
    void normalizesTheBaseUrlAndResolvesPaths() {
        BackendEndpoint endpoint = BackendEndpoint.of("HTTPS://modl.example.com/base//", "key");

        assertEquals("https://modl.example.com/base", endpoint.baseUrl());
        assertEquals("https://modl.example.com/base/v2/minecraft/startup", endpoint.resolve("/v2/minecraft/startup"));
    }

    @Test
    void rejectsUnsupportedUrls() {
        for (String url : new String[]{"ftp://example.com", "http:///missing-host", "://bad", "http://user:pass@host",
                "http://host/path?query=1", ""}) {
            assertThrows(IllegalArgumentException.class, () -> BackendEndpoint.of(url, "key"), url);
        }
    }

    @Test
    void derivesTheRealtimeUrlFromTheEndpoint() {
        assertEquals("ws://127.0.0.1:8080/v1/realtime/ws",
                BackendEndpoint.of("http://127.0.0.1:8080", "key").realtimeUri().toString());
        assertEquals("wss://modl.example.com/v1/realtime/ws",
                BackendEndpoint.of("https://modl.example.com/", "key").realtimeUri().toString());
        assertEquals("wss://modl.example.com:8443/modl/v1/realtime/ws",
                BackendEndpoint.of("https://modl.example.com:8443/modl", "key").realtimeUri().toString());
    }

    @Test
    void resolvesRelativeAndAbsoluteReferences() {
        BackendEndpoint endpoint = BackendEndpoint.of("http://10.0.0.5:8080/modl", "key");

        assertEquals("http://10.0.0.5:8080/modl/v1/storage/objects/abc?sig=1",
                endpoint.resolveReference("/v1/storage/objects/abc?sig=1"));
        assertEquals("https://bucket.s3.example.com/key?X-Amz=1",
                endpoint.resolveReference("https://bucket.s3.example.com/key?X-Amz=1"));
        assertThrows(IllegalArgumentException.class, () -> endpoint.resolveReference("//evil.example.com/x"));
        assertThrows(IllegalArgumentException.class, () -> endpoint.resolveReference("file:///etc/passwd"));
    }

    @Test
    void acceptsHostNamesWithUnderscores() {
        BackendEndpoint endpoint = BackendEndpoint.of("http://modl_backend:8080/", "key");

        assertEquals("http://modl_backend:8080", endpoint.baseUrl());
        assertEquals("modl_backend", endpoint.getAddress().getAddress().getHost());
        assertEquals("ws://modl_backend:8080/v1/realtime/ws", endpoint.realtimeUri().toString());
        assertEquals("http://modl_backend:8080/v1/storage/objects/a", endpoint.resolveReference("/v1/storage/objects/a"));
    }

    @Test
    void reportsInvalidPortsAndHostsAccurately() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> BackendEndpoint.of("http://host:70000", "key"))
                .getMessage().contains("not between 1 and 65535"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> BackendEndpoint.of("http://host:http", "key"))
                .getMessage().contains("invalid port"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> BackendEndpoint.of("http://bad host:80", "key"))
                .getMessage().contains("not a valid URL"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> BackendEndpoint.of("http://-bad-:80", "key"))
                .getMessage().contains("invalid host name"));
        assertEquals("http://[::1]:8080", BackendEndpoint.of("http://[::1]:8080", "key").baseUrl());
    }

    @Test
    void neverExposesTheApiKeyInToString() {
        assertFalse(BackendEndpoint.of("http://127.0.0.1:8080", "secret-key").toString().contains("secret-key"));
    }

    @Test
    void addressKeepsTheBasePath() {
        BackendAddress address = BackendEndpoint.of("https://modl.example.com/modl", "key").getAddress();

        assertEquals("https://modl.example.com/modl", address.toBaseUrl());
        assertEquals("https://modl.example.com/modl/v1/storage", address.resolveReference("/v1/storage"));
    }

    @Test
    void embeddedEndpointsUseLoopbackForWildcardBindAddresses() {
        EmbeddedSettings wildcard = EmbeddedSettings.builder().port(18123).build();
        EmbeddedSettings specific = EmbeddedSettings.builder().port(18123).bindAddress("10.0.0.4").build();

        assertEquals("http://127.0.0.1:18123", BackendEndpointResolver.embedded(wildcard, "internal").baseUrl());
        assertEquals("http://10.0.0.4:18123", BackendEndpointResolver.embedded(specific, "internal").baseUrl());
        assertEquals("internal", BackendEndpointResolver.embedded(wildcard, "internal").getApiKey());
    }

    @Test
    void remoteEndpointsHonorTheTestOverride() {
        RemoteSettings remote = RemoteSettings.builder().url("https://modl.example.com").apiKey("key").build();
        RecordingPluginLogger logger = new RecordingPluginLogger();

        assertEquals("https://modl.example.com", new BackendEndpointResolver(null).remote(remote, logger).baseUrl());
        BackendEndpoint overridden = new BackendEndpointResolver("http://127.0.0.1:18080/").remote(remote, logger);
        assertEquals("http://127.0.0.1:18080", overridden.baseUrl());
        assertEquals("key", overridden.getApiKey());
        assertTrue(logger.infos().stream().anyMatch(line -> line.contains("overridden")));
    }

    @Test
    void anUnusableOverrideIsAConfigurationError() {
        RemoteSettings remote = RemoteSettings.builder().url("https://modl.example.com").apiKey("key").build();

        ConnectionConfigException failure = assertThrows(ConnectionConfigException.class,
                () -> new BackendEndpointResolver("modl.example.com:8080").remote(remote, new RecordingPluginLogger()));

        assertTrue(failure.getMessage().contains("MODL_API_URL"));
    }
}
