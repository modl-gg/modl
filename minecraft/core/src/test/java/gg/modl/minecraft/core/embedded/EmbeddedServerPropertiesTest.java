package gg.modl.minecraft.core.embedded;

import gg.modl.minecraft.core.connection.AiSettings;
import gg.modl.minecraft.core.connection.DatabaseSettings;
import gg.modl.minecraft.core.connection.EmbeddedSettings;
import gg.modl.minecraft.core.connection.SmtpSettings;
import gg.modl.minecraft.core.connection.TunnelSettings;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EmbeddedServerPropertiesTest {
    private final EmbeddedServerPaths paths = EmbeddedServerPaths.forPluginDataFolder(Paths.get("/srv/mc/plugins/modl"));

    @Test
    void mapsConnectionSettingsToServerProperties() {
        EmbeddedSettings settings = EmbeddedSettings.builder()
                .port(18123)
                .publicUrl("https://panel.example.com")
                .trustProxyHeaders(true)
                .database(DatabaseSettings.builder().url("jdbc:postgresql://db.example.com/modl").username("modl")
                        .password("secret").sqliteNativeDir("/opt/modl-native").build())
                .smtp(SmtpSettings.builder().host("smtp.example.com").port(465).from("modl@example.com").starttls(false).build())
                .build();

        Map<String, String> properties = EmbeddedServerProperties.of(settings, paths, "internal-key", Collections.emptyMap());

        assertEquals("18123", properties.get("MODL_PORT"));
        assertEquals("0.0.0.0", properties.get("MODL_BIND_ADDRESS"));
        assertEquals("https://panel.example.com", properties.get("MODL_PUBLIC_URL"));
        assertEquals("true", properties.get("MODL_TRUST_PROXY_HEADERS"));
        assertEquals("private", properties.get("MODL_TRUSTED_PROXY"));
        assertEquals("jdbc:postgresql://db.example.com/modl", properties.get("MODL_DATABASE_URL"));
        assertEquals("modl", properties.get("MODL_DATABASE_USERNAME"));
        assertEquals("secret", properties.get("MODL_DATABASE_PASSWORD"));
        assertEquals("/opt/modl-native", properties.get("MODL_SQLITE_NATIVE_DIR"));
        assertEquals("smtp.example.com", properties.get("MODL_SMTP_HOST"));
        assertEquals("465", properties.get("MODL_SMTP_PORT"));
        assertEquals("false", properties.get("MODL_SMTP_STARTTLS"));
        assertEquals("internal-key", properties.get("MODL_INTERNAL_API_KEY"));
        assertFalse(properties.containsKey("MODL_SERVER_NAME"));
        assertEquals(Paths.get("/srv/mc/plugins/modl/data").toString(), properties.get("MODL_DATA_DIR"));
        assertEquals(Paths.get("/srv/mc/plugins/modl/server/logs").toString(), properties.get("MODL_LOG_DIR"));
        assertEquals(Paths.get("/srv/mc/plugins/modl/setup-code.txt").toString(), properties.get("MODL_SETUP_CODE_FILE"));
    }

    @Test
    void forwardsTheTunnelWithItsLoopbackNetworkDefaults() {
        EmbeddedSettings settings = EmbeddedSettings.builder()
                .publicUrl("https://panel.example.com")
                .tunnel(TunnelSettings.builder().provider("cloudflare").token(" tunnel-token ").build())
                .build()
                .withTunnelNetworkDefaults();

        Map<String, String> properties = EmbeddedServerProperties.of(settings, paths, "internal-key", Collections.emptyMap());

        assertEquals("cloudflare", properties.get("MODL_TUNNEL"));
        assertEquals("tunnel-token", properties.get("MODL_TUNNEL_TOKEN"));
        assertEquals("127.0.0.1", properties.get("MODL_BIND_ADDRESS"));
        assertEquals("true", properties.get("MODL_TRUST_PROXY_HEADERS"));
        assertEquals("loopback", properties.get("MODL_TRUSTED_PROXY"));
        assertFalse(settings.toString().contains("tunnel-token"));
    }

    @Test
    void withoutATunnelNothingIsForwarded() {
        Map<String, String> properties = EmbeddedServerProperties.of(EmbeddedSettings.builder().build(), paths, "key",
                Collections.emptyMap());

        assertFalse(properties.containsKey("MODL_TUNNEL"));
        assertFalse(properties.containsKey("MODL_TUNNEL_TOKEN"));
    }

    @Test
    void passesTheForwardedSwitchesOn() {
        Map<String, String> switches = Collections.singletonMap("MODL_UPDATE_CHECK", "false");

        Map<String, String> properties =
                EmbeddedServerProperties.of(EmbeddedSettings.builder().build(), paths, "key", switches);

        assertEquals("false", properties.get("MODL_UPDATE_CHECK"));
    }

    @Test
    void forwardsTheAiConnectionAsItsEnvironmentSettings() {
        EmbeddedSettings settings = EmbeddedSettings.builder()
                .ai(AiSettings.builder().provider("ollama").apiKey(" sk-secret ").model("llama").baseUrl("http://10.0.0.5:11434/v1").build())
                .build();

        Map<String, String> properties = EmbeddedServerProperties.of(settings, paths, "internal-key", Collections.emptyMap());

        assertEquals("ollama", properties.get("MODL_AI_PROVIDER"));
        assertEquals("sk-secret", properties.get("MODL_AI_API_KEY"));
        assertEquals("llama", properties.get("MODL_AI_MODEL"));
        assertEquals("http://10.0.0.5:11434/v1", properties.get("MODL_AI_BASE_URL"));
        assertFalse(properties.containsKey("MODL_GEMINI_API_KEY"));
        assertFalse(settings.toString().contains("sk-secret"));
    }

    @Test
    void leavesBlankSettingsToTheServerDefaults() {
        Map<String, String> properties = EmbeddedServerProperties.of(EmbeddedSettings.builder().build(), paths, "key",
                Collections.emptyMap());

        assertFalse(properties.containsKey("MODL_PUBLIC_URL"));
        assertFalse(properties.containsKey("MODL_ADMIN_EMAIL"));
        assertFalse(properties.containsKey("MODL_DATABASE_URL"));
        assertFalse(properties.containsKey("MODL_DATABASE_USERNAME"));
        assertFalse(properties.containsKey("MODL_DATABASE_PASSWORD"));
        assertFalse(properties.containsKey("MODL_SQLITE_NATIVE_DIR"));
        assertFalse(properties.containsKey("MODL_AI_PROVIDER"));
        assertFalse(properties.containsKey("MODL_AI_API_KEY"));
        assertFalse(properties.containsKey("MODL_AI_MODEL"));
        assertFalse(properties.containsKey("MODL_AI_BASE_URL"));
        assertFalse(properties.containsKey("MODL_GEMINI_API_KEY"));
        assertFalse(properties.containsKey("MODL_SMTP_HOST"));
        assertFalse(properties.containsKey("MODL_SMTP_PORT"));
        assertFalse(properties.containsKey("MODL_SERVER_NAME"));
    }
}
