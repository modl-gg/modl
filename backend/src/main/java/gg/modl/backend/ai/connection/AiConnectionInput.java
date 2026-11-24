package gg.modl.backend.ai.connection;

import org.jspecify.annotations.Nullable;

public record AiConnectionInput(@Nullable String baseUrl, @Nullable String apiKey, @Nullable String model, boolean activate) {
    @Override
    public String toString() {
        return "AiConnectionInput[baseUrl=" + baseUrl + ", apiKey=" + (apiKey == null ? "absent" : "given") + ", model=" + model
            + ", activate=" + activate + "]";
    }
}
