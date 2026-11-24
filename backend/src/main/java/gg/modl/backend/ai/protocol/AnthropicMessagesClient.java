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
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
@RequiredArgsConstructor
public class AnthropicMessagesClient implements AiProtocolClient {
    static final String API_VERSION = "2026-10-21";
    private static final String LOW_EFFORT = "low";
    private static final String RETIRED = "retired";
    private static final String DEPRECATED = "deprecated";

    private final AiProviderCall call;

    @Override
    public AiProtocol protocol() {
        return AiProtocol.ANTHROPIC_MESSAGES;
    }

    @Override
    public AiOutcome complete(AiConnection connection, AiCompletionRequest request, AiRequestShape shape, AiDeadline deadline) {
        try {
            JsonNode response = call.completion(AiHttpRequest.postJson(AiProviderCall.resolve(connection.baseUrl(), "/messages"),
                headers(connection), AiJson.write(body(connection, request, shape)), AiHttpRequest.COMPLETION_LIMIT), deadline);
            return new AiOutcome.Completed(content(response), shape);
        } catch (AiCallFailedException failed) {
            return new AiOutcome.Failed(failed.failure(), shape);
        }
    }

    @Override
    public AiModelListing listModels(AiConnection connection, AiDeadline deadline) {
        List<AiModelInfo> models = new ArrayList<>();
        String afterId = null;
        try {
            for (int page = 0; page < AiProviderCall.MAX_MODEL_PAGES; page++) {
                String query = "/models?limit=1000" + (afterId == null ? "" : "&after_id=" + URLEncoder.encode(afterId, StandardCharsets.UTF_8));
                JsonNode body = call.listing(AiHttpRequest.get(AiProviderCall.resolve(connection.baseUrl(), query), headers(connection),
                    AiHttpRequest.MODEL_PAGE_LIMIT), deadline);
                for (JsonNode entry : body.path("data").values()) {
                    String id = AiJson.text(entry, "id");
                    if (id == null || id.isBlank() || RETIRED.equalsIgnoreCase(AiJson.text(entry, "lifecycle"))) {
                        continue;
                    }
                    if (models.size() >= AiProviderCall.MAX_MODELS) {
                        return new AiModelListing(models, null, false);
                    }
                    models.add(model(id, entry));
                }
                afterId = AiJson.text(body, "last_id");
                if (!body.path("has_more").booleanValue(false) || afterId == null) {
                    return new AiModelListing(models, null, true);
                }
            }
            return new AiModelListing(models, null, false);
        } catch (AiCallFailedException failed) {
            return AiModelListing.failed(failed.failure().kind());
        }
    }

    static ObjectNode body(AiConnection connection, AiCompletionRequest request, AiRequestShape shape) {
        ObjectNode body = AiJson.object();
        body.put("model", connection.model());
        body.put("max_tokens", request.maxOutputTokens());
        body.put("system", request.system());
        body.putArray("messages").addObject().put("role", "user").put("content", request.user());
        ObjectNode outputConfig = AiJson.object();
        if (shape.effortHint()) {
            outputConfig.put("effort", LOW_EFFORT);
        }
        if (shape.outputMode() == AiRequestShape.OutputMode.SCHEMA) {
            outputConfig.putObject("format").put("type", "json_schema").set("schema", AiJson.tree(request.schema().schema()));
        }
        if (!outputConfig.isEmpty()) {
            body.set("output_config", outputConfig);
        }
        return body;
    }

    private static String content(JsonNode response) throws AiCallFailedException {
        String stopReason = AiJson.text(response, "stop_reason");
        if ("max_tokens".equals(stopReason) || "model_context_window_exceeded".equals(stopReason)) {
            throw new AiCallFailedException(AiFailureKind.TRUNCATED);
        }
        if ("refusal".equals(stopReason)) {
            throw new AiCallFailedException(AiFailureKind.REFUSED);
        }
        if (!"end_turn".equals(stopReason)) {
            throw new AiCallFailedException(AiFailureKind.INVALID_RESPONSE);
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : response.path("content").values()) {
            if ("text".equals(AiJson.text(block, "type"))) {
                String part = AiJson.text(block, "text");
                text.append(part == null ? "" : part);
            }
        }
        if (text.isEmpty() || text.toString().isBlank()) {
            throw new AiCallFailedException(AiFailureKind.INVALID_RESPONSE);
        }
        return text.toString();
    }

    private static AiModelInfo model(String id, JsonNode entry) {
        JsonNode capabilities = entry.path("capabilities");
        return new AiModelInfo(id, AiJson.text(entry, "display_name"), true,
            DEPRECATED.equalsIgnoreCase(AiJson.text(entry, "lifecycle")) || AiJson.instant(entry.path("deprecated_at")).isPresent(),
            AiJson.instant(entry.path("retires_at")).orElse(null),
            supported(capabilities.path("structured_outputs")),
            supported(capabilities.path("effort").path("low")));
    }

    @Nullable
    private static Boolean supported(JsonNode capability) {
        JsonNode supported = capability.path("supported");
        return supported.isBoolean() ? supported.booleanValue() : null;
    }

    private static Map<String, String> headers(AiConnection connection) {
        Map<String, String> headers = new HashMap<>();
        connection.key().ifPresent(key -> headers.put("x-api-key", key));
        headers.put("anthropic-version", API_VERSION);
        return headers;
    }
}
