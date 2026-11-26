package gg.modl.backend.tunnel;

import gg.modl.backend.infrastructure.web.HttpOrigin;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

public class TunnelEnvironment implements EnvironmentPostProcessor, Ordered {
    static final String PROVIDER_PROPERTY = "modl.tunnel.provider";
    static final String TOKEN_PROPERTY = "modl.tunnel.token";
    static final String PUBLIC_URL_PROPERTY = "modl.instance.public-url";
    static final Map<String, String> NETWORK_DEFAULTS = Map.of(
        "MODL_BIND_ADDRESS", "127.0.0.1",
        "MODL_TRUST_PROXY_HEADERS", "true",
        "MODL_TRUSTED_PROXY", "loopback"
    );
    static final int MAX_TOKEN_LENGTH = 4096;

    private static final Pattern TOKEN_CHARACTERS = Pattern.compile("[A-Za-z0-9+/=_-]+");
    private static final String PROPERTY_SOURCE_NAME = "modlTunnelNetworkDefaults";
    private static final String TOKEN_SETTING = "MODL_TUNNEL_TOKEN or connection.yml (embedded.tunnel.token)";
    private static final String PUBLIC_URL_SETTING = "MODL_PUBLIC_URL or connection.yml (embedded.public-url)";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Optional<TunnelProvider> provider = provider(environment.getProperty(PROVIDER_PROPERTY, ""));
        if (provider.isEmpty()) {
            return;
        }
        requireToken(environment.getProperty(TOKEN_PROPERTY, ""));
        requireHttpsPublicUrl(environment.getProperty(PUBLIC_URL_PROPERTY, ""));
        Map<String, Object> defaults = new LinkedHashMap<>();
        NETWORK_DEFAULTS.forEach((name, value) -> {
            if (!environment.containsProperty(name)) {
                defaults.put(name, value);
            }
        });
        if (!defaults.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }

    static Optional<TunnelProvider> provider(String value) {
        if (value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(TunnelProvider.parse(value).orElseThrow(() -> new TunnelConfigurationException(
            "MODL_TUNNEL (connection.yml embedded.tunnel.provider) must be " + TunnelProvider.allowedValues()
                + " or empty, not '" + value.trim() + "'")));
    }

    static void requireToken(String token) {
        String trimmed = token.trim();
        if (trimmed.isEmpty()) {
            throw new TunnelConfigurationException("The Cloudflare Tunnel needs its token: set " + TOKEN_SETTING
                + " to the token of your tunnel in the Cloudflare dashboard");
        }
        if (!looksLikeTunnelToken(trimmed)) {
            throw new TunnelConfigurationException("The tunnel token in " + TOKEN_SETTING + " is not a Cloudflare Tunnel"
                + " token. Copy only the token, the long text after --token in the install command the Cloudflare dashboard shows");
        }
    }

    static void requireHttpsPublicUrl(String publicUrl) {
        boolean usable = HttpOrigin.parseExact(publicUrl).filter(HttpOrigin::isSecure).filter(origin -> !origin.isLoopback())
            .isPresent();
        if (!usable) {
            String found = publicUrl.isBlank() ? "it is empty" : "found '" + publicUrl.trim() + "'";
            throw new TunnelConfigurationException("The Cloudflare Tunnel needs the panel's https address: set "
                + PUBLIC_URL_SETTING + " to the public hostname of the tunnel, such as https://panel.example.com ("
                + found + ")");
        }
    }

    private static boolean looksLikeTunnelToken(String token) {
        if (token.length() > MAX_TOKEN_LENGTH || !TOKEN_CHARACTERS.matcher(token).matches()) {
            return false;
        }
        try {
            byte[] decoded = token.contains("-") || token.contains("_")
                ? Base64.getUrlDecoder().decode(token)
                : Base64.getDecoder().decode(token);
            String json = new String(decoded, StandardCharsets.UTF_8).trim();
            return json.startsWith("{") && json.contains("\"t\"") && json.contains("\"s\"");
        } catch (IllegalArgumentException notBase64) {
            return false;
        }
    }
}
