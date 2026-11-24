package gg.modl.backend.ai.protocol;

import gg.modl.backend.ai.http.AiHttpRequest;
import gg.modl.backend.ai.model.AiCallFailure;
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
import gg.modl.backend.ai.model.OpenAiChatDialect;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
@RequiredArgsConstructor
public class OpenAiChatClient implements AiProtocolClient {
    private static final String LOW_EFFORT = "low";
    private static final Set<String> TRUNCATED = Set.of("length", "model_context_window_exceeded");
    private static final Set<String> FILTERED = Set.of("content_filter", "sensitive");
    private static final Set<String> TRANSIENT = Set.of("error", "network_error", "insufficient_system_resource", "aborted");

    private final AiProviderCall call;

    @Override
    public AiProtocol protocol() {
        return AiProtocol.OPENAI_CHAT;
    }

    @Override
    public AiOutcome complete(AiConnection connection, AiCompletionRequest request, AiRequestShape shape, AiDeadline deadline) {
        try {
            JsonNode response = call.completion(AiHttpRequest.postJson(AiProviderCall.resolve(connection.baseUrl(), "/chat/completions"),
                headers(connection), AiJson.write(body(connection, request, shape)), AiHttpRequest.COMPLETION_LIMIT), deadline);
            return new AiOutcome.Completed(content(response), shape);
        } catch (AiCallFailedException failed) {
            return new AiOutcome.Failed(failed.failure(), shape);
        }
    }

    @Override
    public AiModelListing listModels(AiConnection connection, AiDeadline deadline) {
        try {
            String keyCheckPath = connection.provider().dialect().keyCheckPath();
            if (keyCheckPath != null) {
                call.completion(AiHttpRequest.get(AiProviderCall.resolve(connection.baseUrl(), keyCheckPath), headers(connection),
                    AiHttpRequest.COMPLETION_LIMIT), deadline);
            }
            JsonNode page = call.listing(AiHttpRequest.get(AiProviderCall.resolve(connection.baseUrl(), "/models"), headers(connection),
                AiHttpRequest.MODEL_PAGE_LIMIT), deadline);
            List<AiModelInfo> models = new ArrayList<>();
            for (JsonNode entry : page.path("data").values()) {
                if (models.size() >= AiProviderCall.MAX_MODELS) {
                    return new AiModelListing(models, null, false);
                }
                String id = AiJson.text(entry, "id");
                if (id != null && !id.isBlank()) {
                    models.add(model(id, entry));
                }
            }
            return new AiModelListing(models, null, true);
        } catch (AiCallFailedException failed) {
            return AiModelListing.failed(failed.failure().kind());
        }
    }

    static ObjectNode body(AiConnection connection, AiCompletionRequest request, AiRequestShape shape) {
        OpenAiChatDialect dialect = connection.provider().dialect();
        ObjectNode body = AiJson.object();
        body.put("model", connection.model());
        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.system());
        messages.addObject().put("role", "user").put("content", request.user());
        body.put(shape.tokenField().jsonName(), request.maxOutputTokens());
        switch (shape.outputMode()) {
            case SCHEMA -> {
                ObjectNode format = body.putObject("response_format").put("type", "json_schema");
                format.putObject("json_schema")
                    .put("name", request.schema().name())
                    .put("strict", true)
                    .set("schema", AiJson.tree(request.schema().schema()));
            }
            case JSON_OBJECT -> body.putObject("response_format").put("type", "json_object");
            case PROMPT_ONLY -> {
            }
        }
        if (dialect.requireParameters() && shape.outputMode() != AiRequestShape.OutputMode.PROMPT_ONLY) {
            body.putObject("provider").put("require_parameters", true);
        }
        if (shape.effortHint()) {
            body.put("reasoning_effort", LOW_EFFORT);
        }
        dialect.extraBody().forEach((name, value) -> body.set(name, AiJson.tree(value)));
        return body;
    }

    private static String content(JsonNode response) throws AiCallFailedException {
        JsonNode topError = response.path("error");
        if (topError.isObject()) {
            throw new AiCallFailedException(AiErrorClassifier.embeddedError(topError));
        }
        JsonNode minimaxStatus = response.path("base_resp").path("status_code");
        if (minimaxStatus.isIntegralNumber() && minimaxStatus.intValue() != 0) {
            throw new AiCallFailedException(AiErrorClassifier.minimaxStatus(minimaxStatus.intValue()));
        }
        JsonNode choice = response.path("choices").path(0);
        JsonNode choiceError = choice.path("error");
        if (choiceError.isObject()) {
            throw new AiCallFailedException(AiErrorClassifier.embeddedError(choiceError));
        }
        JsonNode message = choice.path("message");
        String refusal = AiJson.text(message, "refusal");
        if (refusal != null && !refusal.isBlank()) {
            throw new AiCallFailedException(AiFailureKind.REFUSED);
        }
        String finishReason = AiJson.text(choice, "finish_reason");
        failOnFinishReason(finishReason);
        String content = AiJson.text(message, "content");
        if (content == null || content.isBlank()) {
            throw new AiCallFailedException(AiFailureKind.INVALID_RESPONSE);
        }
        return content;
    }

    private static void failOnFinishReason(@Nullable String finishReason) throws AiCallFailedException {
        if ("stop".equals(finishReason)) {
            return;
        }
        if (finishReason != null && TRUNCATED.contains(finishReason)) {
            throw new AiCallFailedException(AiFailureKind.TRUNCATED);
        }
        if (finishReason != null && FILTERED.contains(finishReason)) {
            throw new AiCallFailedException(AiFailureKind.FILTERED);
        }
        if (finishReason != null && TRANSIENT.contains(finishReason)) {
            throw new AiCallFailedException(AiCallFailure.retryable(AiFailureKind.PROVIDER_ERROR));
        }
        throw new AiCallFailedException(AiFailureKind.INVALID_RESPONSE);
    }

    private static AiModelInfo model(String id, JsonNode entry) {
        JsonNode modalities = entry.path("architecture").path("output_modalities");
        if (!modalities.isArray()) {
            modalities = entry.path("output_modalities");
        }
        boolean textOutput = !modalities.isArray() || AiJson.arrayContains(modalities, "text");
        JsonNode parameters = entry.path("supported_parameters");
        Boolean structured = parameters.isArray() ? AiJson.arrayContains(parameters, "structured_outputs") : null;
        var retiresAt = AiJson.instant(entry.path("shutdown_date")).or(() -> AiJson.instant(entry.path("expiration_date"))).orElse(null);
        return new AiModelInfo(id, AiJson.text(entry, "name"), textOutput, false, retiresAt, structured, null);
    }

    private static Map<String, String> headers(AiConnection connection) {
        Map<String, String> headers = new HashMap<>();
        connection.key().ifPresent(key -> headers.put("Authorization", "Bearer " + key));
        return headers;
    }
}
