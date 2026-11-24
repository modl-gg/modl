package gg.modl.backend.ai.connection;

import org.jspecify.annotations.Nullable;

public record AiDraft(String provider, @Nullable String baseUrl, @Nullable String apiKey, @Nullable String model) {
    @Override
    public String toString() {
        return "AiDraft[provider=" + provider + ", baseUrl=" + baseUrl + ", apiKey=" + (apiKey == null ? "absent" : "given")
            + ", model=" + model + "]";
    }
}
