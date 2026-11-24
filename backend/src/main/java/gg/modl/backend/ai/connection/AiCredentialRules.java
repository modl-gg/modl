package gg.modl.backend.ai.connection;

import gg.modl.backend.ai.model.AiProtocol;
import gg.modl.backend.ai.model.AiProviderDefinition;
import gg.modl.backend.ai.protocol.GeminiGenerateContentClient;
import org.jspecify.annotations.Nullable;
import java.util.regex.Pattern;

public final class AiCredentialRules {
    public static final String KEY_INVALID = "AI_KEY_INVALID";
    public static final String MODEL_INVALID = "AI_MODEL_INVALID";

    private static final Pattern API_KEY = Pattern.compile("[\\x21-\\x7E]{1,512}");
    private static final Pattern MODEL_ID = Pattern.compile("[\\x21-\\x7E]{1,200}");

    private AiCredentialRules() {
    }

    public static boolean validKey(@Nullable String key) {
        return key != null && API_KEY.matcher(key).matches();
    }

    public static boolean validModel(AiProviderDefinition provider, @Nullable String model) {
        if (model == null || !MODEL_ID.matcher(model).matches()) {
            return false;
        }
        return provider.protocol() != AiProtocol.GEMINI_GENERATE || GeminiGenerateContentClient.pathModel(model).isPresent();
    }
}
