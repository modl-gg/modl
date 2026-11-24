package gg.modl.backend.ai.protocol;

import gg.modl.backend.ai.http.AiHttpRequest;
import gg.modl.backend.ai.model.AiCompletionRequest;
import gg.modl.backend.ai.model.AiConnection;
import gg.modl.backend.ai.model.AiDeadline;
import gg.modl.backend.ai.model.AiFailureKind;
import gg.modl.backend.ai.model.AiModelInfo;
import gg.modl.backend.ai.model.AiModelListing;
import gg.modl.backend.ai.model.AiOutcome;
import gg.modl.backend.ai.model.AiProtocol;
import gg.modl.backend.ai.model.AiProtocolClient;
import gg.modl.backend.ai.model.AiRequestShape;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
@RequiredArgsConstructor
public class GeminiGenerateContentClient implements AiProtocolClient {
    public static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,199}");
    private static final String MODEL_PREFIX = "models/";
    private static final String JSON_MIME_TYPE = "APPLICATION_JSON";
    private static final Set<String> TRUNCATED = Set.of("MAX_TOKENS", "CONTINUATION");
    private static final Set<String> FILTERED = Set.of(
        "SAFETY", "RECITATION", "LANGUAGE", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "ESCALATION");
    private static final String PERMISSION = "PUP_LIMITED_DISABLED";

    private final AiProviderCall call;

    public static Optional<String> pathModel(@Nullable String model) {
        if (model == null) {
            return Optional.empty();
        }
        String id = model.startsWith(MODEL_PREFIX) ? model.substring(MODEL_PREFIX.length()) : model;
        return MODEL_ID.matcher(id).matches() ? Optional.of(id) : Optional.empty();
    }

    @Override
    public AiProtocol protocol() {
        return AiProtocol.GEMINI_GENERATE;
    }

    @Override
    public AiOutcome complete(AiConnection connection, AiCompletionRequest request, AiRequestShape shape, AiDeadline deadline) {
        try {
            String model = pathModel(connection.model()).orElseThrow(() -> new AiCallFailedException(AiFailureKind.INVALID_REQUEST));
            JsonNode response = call.completion(AiHttpRequest.postJson(
                AiProviderCall.resolve(connection.baseUrl(), "/models/" + model + ":generateContent"),
                headers(connection), AiJson.write(body(request, shape)), AiHttpRequest.COMPLETION_LIMIT), deadline);
            return new AiOutcome.Completed(content(response), shape);
        } catch (AiCallFailedException failed) {
            return new AiOutcome.Failed(failed.failure(), shape);
        }
    }

    @Override
    public AiModelListing listModels(AiConnection connection, AiDeadline deadline) {
        List<AiModelInfo> models = new ArrayList<>();
        String pageToken = null;
        try {
            for (int page = 0; page < AiProviderCall.MAX_MODEL_PAGES; page++) {
                String query = "/models?pageSize=1000"
                    + (pageToken == null ? "" : "&pageToken=" + URLEncoder.encode(pageToken, StandardCharsets.UTF_8));
                JsonNode body = call.listing(AiHttpRequest.get(AiProviderCall.resolve(connection.baseUrl(), query), headers(connection),
                    AiHttpRequest.MODEL_PAGE_LIMIT), deadline);
                for (JsonNode entry : body.path("models").values()) {
                    Optional<String> id = pathModel(AiJson.text(entry, "name"));
                    if (id.isEmpty()) {
                        continue;
                    }
                    if (models.size() >= AiProviderCall.MAX_MODELS) {
                        return new AiModelListing(models, null, false);
                    }
                    models.add(new AiModelInfo(id.get(), AiJson.text(entry, "displayName"),
                        AiJson.arrayContains(entry.path("supportedGenerationMethods"), "generateContent"), false, null, null, null));
                }
                pageToken = AiJson.text(body, "nextPageToken");
                if (pageToken == null || pageToken.isBlank()) {
                    return new AiModelListing(models, null, true);
                }
            }
            return new AiModelListing(models, null, false);
        } catch (AiCallFailedException failed) {
            return AiModelListing.failed(failed.failure().kind());
        }
    }

    static ObjectNode body(AiCompletionRequest request, AiRequestShape shape) {
        ObjectNode body = AiJson.object();
        body.putObject("systemInstruction").putArray("parts").addObject().put("text", request.system());
        ObjectNode content = body.putArray("contents").addObject().put("role", "user");
        content.putArray("parts").addObject().put("text", request.user());
        ObjectNode generation = body.putObject("generationConfig").put("maxOutputTokens", request.maxOutputTokens());
        switch (shape.outputMode()) {
            case SCHEMA -> generation.putObject("responseFormat").putObject("text")
                .put("mimeType", JSON_MIME_TYPE).set("schema", AiJson.tree(request.schema().schema()));
            case JSON_OBJECT -> generation.putObject("responseFormat").putObject("text").put("mimeType", JSON_MIME_TYPE);
            case PROMPT_ONLY -> {
            }
        }
        return body;
    }

    private static String content(JsonNode response) throws AiCallFailedException {
        if (AiJson.text(response.path("promptFeedback"), "blockReason") != null) {
            throw new AiCallFailedException(AiFailureKind.FILTERED);
        }
        JsonNode candidate = response.path("candidates").path(0);
        String finishReason = AiJson.text(candidate, "finishReason");
        if (finishReason != null && TRUNCATED.contains(finishReason)) {
            throw new AiCallFailedException(AiFailureKind.TRUNCATED);
        }
        if (finishReason != null && FILTERED.contains(finishReason)) {
            throw new AiCallFailedException(AiFailureKind.FILTERED);
        }
        if (PERMISSION.equals(finishReason)) {
            throw new AiCallFailedException(AiFailureKind.PERMISSION);
        }
        if (!"STOP".equals(finishReason)) {
            throw new AiCallFailedException(AiFailureKind.INVALID_RESPONSE);
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts").values()) {
            if (!part.path("thought").booleanValue(false)) {
                String piece = AiJson.text(part, "text");
                text.append(piece == null ? "" : piece);
            }
        }
        if (text.toString().isBlank()) {
            throw new AiCallFailedException(AiFailureKind.INVALID_RESPONSE);
        }
        return text.toString();
    }

    private static Map<String, String> headers(AiConnection connection) {
        Map<String, String> headers = new HashMap<>();
        connection.key().ifPresent(key -> headers.put("x-goog-api-key", key));
        return headers;
    }
}
