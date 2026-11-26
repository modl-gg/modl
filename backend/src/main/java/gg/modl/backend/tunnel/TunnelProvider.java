package gg.modl.backend.tunnel;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

public enum TunnelProvider {
    CLOUDFLARE("cloudflare");

    private final String configValue;

    TunnelProvider(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    public static Optional<TunnelProvider> parse(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(provider -> provider.configValue.equals(normalized)).findFirst();
    }

    static String allowedValues() {
        return Arrays.stream(values()).map(TunnelProvider::configValue).collect(Collectors.joining(", "));
    }
}
