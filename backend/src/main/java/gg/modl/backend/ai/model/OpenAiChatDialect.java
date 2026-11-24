package gg.modl.backend.ai.model;

import org.jspecify.annotations.Nullable;
import java.util.Map;

public record OpenAiChatDialect(Map<String, Object> extraBody, boolean requireParameters, @Nullable String keyCheckPath) {
    public static final OpenAiChatDialect STANDARD = new OpenAiChatDialect(Map.of(), false, null);

    public OpenAiChatDialect {
        extraBody = Map.copyOf(extraBody);
    }
}
