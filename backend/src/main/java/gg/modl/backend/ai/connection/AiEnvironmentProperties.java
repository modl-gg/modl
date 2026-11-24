package gg.modl.backend.ai.connection;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "modl.ai")
public record AiEnvironmentProperties(
    @Nullable String provider,
    @Nullable String apiKey,
    @Nullable String model,
    @Nullable String baseUrl,
    @Nullable String geminiApiKey
) {
    public static final AiEnvironmentProperties NONE = new AiEnvironmentProperties(null, null, null, null, null);

    public AiEnvironmentProperties {
        provider = trimToNull(provider);
        apiKey = trimToNull(apiKey);
        model = trimToNull(model);
        baseUrl = trimToNull(baseUrl);
        geminiApiKey = trimToNull(geminiApiKey);
    }

    @Override
    public String toString() {
        return "AiEnvironmentProperties[provider=" + provider + ", apiKey=" + (apiKey == null ? "none" : "set") + ", model=" + model
            + ", baseUrl=" + baseUrl + ", geminiApiKey=" + (geminiApiKey == null ? "none" : "set") + "]";
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
