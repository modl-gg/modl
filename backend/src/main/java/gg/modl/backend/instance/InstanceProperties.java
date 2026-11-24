package gg.modl.backend.instance;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "modl.instance")
public record InstanceProperties(
    @Nullable String publicUrl,
    @Nullable String adminEmail,
    @Nullable String serverName,
    @Nullable String apiKey,
    @Nullable String internalApiKey,
    @Nullable Boolean trustProxyHeaders
) {
    public static final String DEFAULT_SERVER_NAME = "Minecraft Server";
    public static final int MIN_API_KEY_LENGTH = 32;
    public static final int MAX_API_KEY_LENGTH = 255;

    public InstanceProperties {
        publicUrl = trimToNull(publicUrl);
        adminEmail = trimToNull(adminEmail);
        serverName = ServerNames.normalize(serverName).orElse(null);
        apiKey = trimToNull(apiKey);
        internalApiKey = trimToNull(internalApiKey);
        trustProxyHeaders = Boolean.TRUE.equals(trustProxyHeaders);
    }

    public Optional<String> configuredPublicUrl() {
        return Optional.ofNullable(publicUrl);
    }

    public Optional<String> configuredAdminEmail() {
        return Optional.ofNullable(adminEmail);
    }

    public Optional<String> configuredServerName() {
        return Optional.ofNullable(serverName);
    }

    public String initialServerName() {
        return configuredServerName().orElse(DEFAULT_SERVER_NAME);
    }

    public Optional<String> configuredApiKey() {
        return Optional.ofNullable(apiKey);
    }

    public Optional<String> configuredInternalApiKey() {
        return Optional.ofNullable(internalApiKey);
    }

    public boolean isEmbedded() {
        return internalApiKey != null;
    }

    public boolean trustsProxyHeaders() {
        return Boolean.TRUE.equals(trustProxyHeaders);
    }

    @Override
    public String toString() {
        return "InstanceProperties[publicUrl=" + publicUrl + ", adminEmail=" + adminEmail + ", serverName=" + serverName
            + ", apiKey=" + redacted(apiKey) + ", internalApiKey=" + redacted(internalApiKey)
            + ", trustProxyHeaders=" + trustProxyHeaders + "]";
    }

    private static String redacted(@Nullable String secret) {
        return secret == null ? "none" : "redacted";
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
