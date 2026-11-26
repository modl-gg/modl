package gg.modl.backend.tunnel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.infrastructure.config.TrustedProxyHeadersPostProcessor;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class TunnelEnvironmentTest {
    static final String TOKEN = Base64.getEncoder().encodeToString(
        "{\"a\":\"0123456789abcdef0123456789abcdef\",\"t\":\"6f1c2a4e-8b3d-4c5e-9f70-1a2b3c4d5e6f\",\"s\":\"c2VjcmV0\"}"
            .getBytes(StandardCharsets.UTF_8));

    @Test
    void withoutATunnelNothingChanges() {
        StandardEnvironment environment = environment(Map.of());

        assertThat(environment.getProperty("server.address")).isEqualTo("0.0.0.0");
        assertThat(environment.getProperty("modl.instance.trust-proxy-headers")).isEqualTo("false");
        assertThat(environment.containsProperty("server.forward-headers-strategy")).isFalse();
    }

    @Test
    void theTunnelListensOnLoopbackAndTrustsOnlyTheLocalAgent() {
        StandardEnvironment environment = environment(tunnel("https://panel.example.com"));

        assertThat(environment.getProperty("server.address")).isEqualTo("127.0.0.1");
        assertThat(environment.getProperty("modl.instance.trust-proxy-headers")).isEqualTo("true");
        assertThat(environment.getProperty("modl.instance.trusted-proxy")).isEqualTo("loopback");
        assertThat(environment.getProperty("server.forward-headers-strategy")).isEqualTo("native");
        assertThat("127.0.0.1").matches(environment.getProperty("server.tomcat.remoteip.internal-proxies"));
        assertThat("172.17.0.2").doesNotMatch(environment.getProperty("server.tomcat.remoteip.internal-proxies"));
    }

    @Test
    void explicitNetworkSettingsWinOverTheTunnelDefaults() {
        Map<String, Object> properties = tunnel("https://panel.example.com");
        properties.put("MODL_BIND_ADDRESS", "0.0.0.0");
        properties.put("MODL_TRUSTED_PROXY", "private");
        properties.put("MODL_TRUST_PROXY_HEADERS", "false");

        StandardEnvironment environment = environment(properties);

        assertThat(environment.getProperty("server.address")).isEqualTo("0.0.0.0");
        assertThat(environment.getProperty("modl.instance.trusted-proxy")).isEqualTo("private");
        assertThat(environment.getProperty("modl.instance.trust-proxy-headers")).isEqualTo("false");
    }

    @Test
    void theTunnelNeedsAnHttpsPublicUrl() {
        assertThatThrownBy(() -> environment(tunnel("http://panel.example.com")))
            .isInstanceOf(TunnelConfigurationException.class)
            .hasMessageContaining("https address").hasMessageContaining("MODL_PUBLIC_URL")
            .hasMessageContaining("embedded.public-url").hasMessageContaining("http://panel.example.com");
        assertThatThrownBy(() -> environment(tunnel(""))).hasMessageContaining("it is empty");
        assertThatThrownBy(() -> environment(tunnel("https://localhost"))).isInstanceOf(TunnelConfigurationException.class);
        assertThatThrownBy(() -> environment(tunnel("https://panel.example.com/panel")))
            .isInstanceOf(TunnelConfigurationException.class);
    }

    @Test
    void theTunnelNeedsAPlausibleTokenAndNeverEchoesIt() {
        Map<String, Object> missing = tunnel("https://panel.example.com");
        missing.remove("MODL_TUNNEL_TOKEN");
        assertThatThrownBy(() -> environment(missing)).hasMessageContaining("needs its token")
            .hasMessageContaining("MODL_TUNNEL_TOKEN").hasMessageContaining("embedded.tunnel.token");

        Map<String, Object> wholeCommand = tunnel("https://panel.example.com");
        wholeCommand.put("MODL_TUNNEL_TOKEN", "cloudflared service install " + TOKEN);
        assertThatThrownBy(() -> environment(wholeCommand)).hasMessageContaining("is not a Cloudflare Tunnel token")
            .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(TOKEN));
    }

    @Test
    void anUnknownProviderStopsTheStart() {
        Map<String, Object> properties = tunnel("https://panel.example.com");
        properties.put("MODL_TUNNEL", "ngrok");

        assertThatThrownBy(() -> environment(properties)).isInstanceOf(TunnelConfigurationException.class)
            .hasMessageContaining("must be cloudflare");
    }

    @Test
    void thePropertiesNeverPrintTheToken() {
        assertThat(new TunnelProperties("cloudflare", TOKEN).toString()).doesNotContain(TOKEN).contains("redacted");
        assertThat(new TunnelProperties("cloudflare", TOKEN).enabled()).isTrue();
        assertThat(TunnelProperties.off().enabled()).isFalse();
    }

    static Map<String, Object> tunnel(String publicUrl) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("MODL_TUNNEL", "cloudflare");
        properties.put("MODL_TUNNEL_TOKEN", TOKEN);
        properties.put("MODL_PUBLIC_URL", publicUrl);
        return properties;
    }

    private static StandardEnvironment environment(Map<String, Object> variables) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("variables", variables));
        environment.getPropertySources().addLast(new MapPropertySource("application", Map.of(
            "server.address", "${MODL_BIND_ADDRESS:0.0.0.0}",
            "modl.instance.public-url", "${MODL_PUBLIC_URL:}",
            "modl.instance.trust-proxy-headers", "${MODL_TRUST_PROXY_HEADERS:false}",
            "modl.instance.trusted-proxy", "${MODL_TRUSTED_PROXY:private}",
            "modl.tunnel.provider", "${MODL_TUNNEL:}",
            "modl.tunnel.token", "${MODL_TUNNEL_TOKEN:}")));
        new TunnelEnvironment().postProcessEnvironment(environment, new SpringApplication());
        new TrustedProxyHeadersPostProcessor().postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }
}
