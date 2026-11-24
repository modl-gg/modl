package gg.modl.backend.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class TrustedProxyHeadersPostProcessorTest {

    @Test
    void untrustedHeadersChangeNothing() {
        StandardEnvironment environment = environment(Map.of("modl.instance.trusted-proxy", "loopback"));

        assertThat(environment.containsProperty("server.forward-headers-strategy")).isFalse();
        assertThat(environment.containsProperty("server.tomcat.remoteip.internal-proxies")).isFalse();
    }

    @Test
    void privateProxiesKeepTomcatsDefaultInternalAddresses() {
        StandardEnvironment environment = environment(Map.of("modl.instance.trust-proxy-headers", "true"));

        assertThat(environment.getProperty("server.forward-headers-strategy")).isEqualTo("native");
        assertThat(environment.containsProperty("server.tomcat.remoteip.internal-proxies")).isFalse();
    }

    @Test
    void aTunnelAgentOnThisMachineIsTheOnlyTrustedSender() {
        StandardEnvironment environment = environment(Map.of(
            "modl.instance.trust-proxy-headers", "true", "modl.instance.trusted-proxy", "loopback"));

        String proxies = environment.getProperty("server.tomcat.remoteip.internal-proxies");
        assertThat("127.0.0.1").matches(proxies);
        assertThat("::1").matches(proxies);
        assertThat("172.17.0.2").doesNotMatch(proxies);
        assertThat("10.0.0.5").doesNotMatch(proxies);
    }

    @Test
    void anUnknownValueStopsTheStart() {
        assertThatThrownBy(() -> environment(Map.of(
            "modl.instance.trust-proxy-headers", "true", "modl.instance.trusted-proxy", "everyone")))
            .hasMessageContaining("MODL_TRUSTED_PROXY");
    }

    private static StandardEnvironment environment(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        new TrustedProxyHeadersPostProcessor().postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }
}
