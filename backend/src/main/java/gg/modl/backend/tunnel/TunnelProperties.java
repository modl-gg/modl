package gg.modl.backend.tunnel;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "modl.tunnel")
public record TunnelProperties(@Nullable String provider, @Nullable String token) {
    public TunnelProperties {
        provider = provider == null || provider.isBlank() ? null : provider.trim();
        token = token == null || token.isBlank() ? null : token.trim();
    }

    public static TunnelProperties off() {
        return new TunnelProperties(null, null);
    }

    public Optional<TunnelProvider> configuredProvider() {
        return provider == null ? Optional.empty() : TunnelProvider.parse(provider);
    }

    public boolean enabled() {
        return configuredProvider().isPresent() && token != null;
    }

    @Override
    public String toString() {
        return "TunnelProperties[provider=" + provider + ", token=" + (token == null ? "none" : "redacted") + "]";
    }
}
