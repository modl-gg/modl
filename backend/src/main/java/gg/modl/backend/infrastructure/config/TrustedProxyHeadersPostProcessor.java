package gg.modl.backend.infrastructure.config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

public class TrustedProxyHeadersPostProcessor implements EnvironmentPostProcessor, Ordered {
    static final String LOOPBACK_PROXIES = "127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|0:0:0:0:0:0:0:1|::1";
    private static final String TRUST_PROXY_HEADERS_PROPERTY = "modl.instance.trust-proxy-headers";
    private static final String TRUSTED_PROXY_PROPERTY = "modl.instance.trusted-proxy";
    private static final String FORWARD_HEADERS_STRATEGY_PROPERTY = "server.forward-headers-strategy";
    private static final String INTERNAL_PROXIES_PROPERTY = "server.tomcat.remoteip.internal-proxies";
    private static final String NATIVE_STRATEGY = "native";
    private static final String PRIVATE = "private";
    private static final String LOOPBACK = "loopback";
    private static final String PROPERTY_SOURCE_NAME = "modlTrustedProxyHeaders";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean trustProxyHeaders = environment.getProperty(TRUST_PROXY_HEADERS_PROPERTY, Boolean.class, false);
        if (!trustProxyHeaders) {
            return;
        }
        Map<String, Object> derived = new LinkedHashMap<>();
        if (!environment.containsProperty(FORWARD_HEADERS_STRATEGY_PROPERTY)) {
            derived.put(FORWARD_HEADERS_STRATEGY_PROPERTY, NATIVE_STRATEGY);
        }
        if (trustsOnlyLoopback(environment) && !environment.containsProperty(INTERNAL_PROXIES_PROPERTY)) {
            derived.put(INTERNAL_PROXIES_PROPERTY, LOOPBACK_PROXIES);
        }
        if (!derived.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, derived));
        }
    }

    private static boolean trustsOnlyLoopback(ConfigurableEnvironment environment) {
        String trustedProxy = environment.getProperty(TRUSTED_PROXY_PROPERTY, PRIVATE).trim().toLowerCase(Locale.ROOT);
        if (trustedProxy.isEmpty() || PRIVATE.equals(trustedProxy)) {
            return false;
        }
        if (LOOPBACK.equals(trustedProxy)) {
            return true;
        }
        throw new IllegalArgumentException("MODL_TRUSTED_PROXY must be private or loopback, not " + trustedProxy);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
