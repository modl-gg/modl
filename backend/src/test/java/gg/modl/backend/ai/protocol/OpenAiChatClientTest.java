package gg.modl.backend.ai.protocol;

import static gg.modl.backend.ai.protocol.ProtocolFixtures.KEY;
import static gg.modl.backend.ai.protocol.ProtocolFixtures.REQUEST;
import static gg.modl.backend.ai.protocol.ProtocolFixtures.connection;
import static gg.modl.backend.ai.protocol.ProtocolFixtures.deadline;
import static gg.modl.backend.ai.protocol.ProtocolFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.ai.http.LocalAiServer;
import gg.modl.backend.ai.model.AiCallFailure;
import gg.modl.backend.ai.model.AiFailureKind;
import gg.modl.backend.ai.model.AiModelInfo;
import gg.modl.backend.ai.model.AiModelListing;
import gg.modl.backend.ai.model.AiOutcome;
import gg.modl.backend.ai.model.AiRequestShape;
import gg.modl.backend.ai.model.AiRequestShape.OutputMode;
import gg.modl.backend.ai.model.AiRequestShape.TokenField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

class OpenAiChatClientTest {
    private static final String CHAT = "/v1/chat/completions";
    private final OpenAiChatClient client = new OpenAiChatClient(ProtocolFixtures.call());

    @Test
    void theSchemaRequestIsExactAndCarriesNoSamplingFields() throws Exception {
        try (LocalAiServer server = LocalAiServer.start()) {
            server.json(CHAT, 200, answer("stop", "{\"ok\":true}"));

            AiOutcome outcome = client.complete(connection("openai", server, KEY, "model-a"), REQUEST,
                new AiRequestShape(OutputMode.SCHEMA, TokenField.MAX_COMPLETION_TOKENS, true), deadline());

            assertThat(outcome).isInstanceOf(AiOutcome.Completed.class);
            LocalAiServer.Recorded sent = server.requests().getFirst();
            assertThat(sent.header("Authorization")).isEqualTo("Bearer " + KEY);
            JsonNode body = json(sent.body());
            assertThat(body.path("model").asString()).isEqualTo("model-a");
            assertThat(body.path("messages").get(0).path("role").asString()).isEqualTo("system");
            assertThat(body.path("messages").get(1).path("content").asString()).isEqualTo("user text");
            assertThat(body.path("max_completion_tokens").intValue()).isEqualTo(4096);
            assertThat(body.path("response_format").path("type").asString()).isEqualTo("json_schema");
            assertThat(body.path("response_format").path("json_schema").path("strict").booleanValue()).isTrue();
            assertThat(body.path("response_format").path("json_schema").path("schema").path("additionalProperties").booleanValue()).isFalse();
            assertThat(body.path("reasoning_effort").asString()).isEqualTo("low");
            assertThat(body.has("temperature") || body.has("top_p") || body.has("top_k") || body.has("max_tokens")).isFalse();
        }
    }

    @Test
    void jsonObjectModeAndDialectExtrasAreApplied() throws Exception {
        try (LocalAiServer server = LocalAiServer.start()) {
            server.json(CHAT, 200, answer("stop", "{}"));

            client.complete(connection("minimax", server, KEY, "m"), REQUEST,
                new AiRequestShape(OutputMode.PROMPT_ONLY, TokenField.MAX_COMPLETION_TOKENS, false), deadline());
            client.complete(connection("openrouter", server, KEY, "m"), REQUEST,
                new AiRequestShape(OutputMode.JSON_OBJECT, TokenField.MAX_TOKENS, false), deadline());
            client.complete(connection("openrouter", server, KEY, "m"), REQUEST,
                new AiRequestShape(OutputMode.PROMPT_ONLY, TokenField.MAX_TOKENS, false), deadline());

            JsonNode minimax = json(server.requests().get(0).body());
            assertThat(minimax.path("reasoning_split").booleanValue()).isTrue();
            assertThat(minimax.has("response_format")).isFalse();
            JsonNode structured = json(server.requests().get(1).body());
            assertThat(structured.path("response_format").path("type").asString()).isEqualTo("json_object");
            assertThat(structured.path("provider").path("require_parameters").booleanValue()).isTrue();
            assertThat(structured.path("max_tokens").intValue()).isEqualTo(4096);
            assertThat(json(server.requests().get(2).body()).has("provider")).isFalse();
        }
    }

    @Test
    void noAuthorizationHeaderIsSentWithoutAKey() throws Exception {
        try (LocalAiServer server = LocalAiServer.start()) {
            server.json(CHAT, 200, answer("stop", "{}"));

            client.complete(connection("ollama", server, null, "llama"), REQUEST,
                new AiRequestShape(OutputMode.SCHEMA, TokenField.MAX_TOKENS, false), deadline());

            assertThat(server.requests().getFirst().header("Authorization")).isNull();
        }
    }

    @ParameterizedTest
    @CsvSource({"length, TRUNCATED, false", "model_context_window_exceeded, TRUNCATED, false", "content_filter, FILTERED, false",
        "sensitive, FILTERED, false", "network_error, PROVIDER_ERROR, true", "insufficient_system_resource, PROVIDER_ERROR, true",
        "tool_calls, INVALID_RESPONSE, false"})
    void finishReasonsMapToFailures(String finishReason, AiFailureKind kind, boolean retryable) throws Exception {
        AiCallFailure failure = failureFor(answer(finishReason, "{}"));

        assertThat(failure.kind()).isEqualTo(kind);
        assertThat(failure.retryable()).isEqualTo(retryable);
    }

    @Test
    void refusalsBlankContentAndEmbeddedErrorsAreFailures() throws Exception {
        assertThat(failureFor("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":null,\"refusal\":\"no\"}}]}").kind())
            .isEqualTo(AiFailureKind.REFUSED);
        assertThat(failureFor(answer("stop", " ")).kind()).isEqualTo(AiFailureKind.INVALID_RESPONSE);
        assertThat(failureFor("{\"error\":{\"code\":402,\"message\":\"credits\"}}").kind()).isEqualTo(AiFailureKind.QUOTA);
        assertThat(failureFor("{\"choices\":[{\"finish_reason\":\"error\",\"error\":{\"code\":502}}]}").kind())
            .isEqualTo(AiFailureKind.PROVIDER_ERROR);
        assertThat(failureFor("{\"base_resp\":{\"status_code\":1008},\"choices\":[]}").kind()).isEqualTo(AiFailureKind.QUOTA);
        assertThat(failureFor("{\"choices\":[{\"finish_reason\":null,\"message\":{\"content\":\"x\"}}]}").kind())
            .isEqualTo(AiFailureKind.INVALID_RESPONSE);
    }

    @Test
    void reasoningFieldsAreIgnored() throws Exception {
        try (LocalAiServer server = LocalAiServer.start()) {
            server.json(CHAT, 200, "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\",\"reasoning_content\":\"long\"}}]}");

            AiOutcome outcome = client.complete(connection("deepseek", server, KEY, "m"), REQUEST,
                new AiRequestShape(OutputMode.JSON_OBJECT, TokenField.MAX_TOKENS, false), deadline());

            assertThat(((AiOutcome.Completed) outcome).text()).isEqualTo("{}");
        }
    }

    @Test
    void modelListsCarryMetadata() throws Exception {
        try (LocalAiServer server = LocalAiServer.start()) {
            server.json("/v1/models", 200, "{\"data\":["
                + "{\"id\":\"chat-1\",\"name\":\"Chat One\",\"supported_parameters\":[\"structured_outputs\"]},"
                + "{\"id\":\"pic-1\",\"architecture\":{\"output_modalities\":[\"image\"]}},"
                + "{\"id\":\"old-1\",\"expiration_date\":\"2027-01-01\"},"
                + "{\"object\":\"model\"}]}");

            AiModelListing listing = client.listModels(connection("openai", server, KEY, "m"), deadline());

            assertThat(listing.succeeded()).isTrue();
            assertThat(listing.complete()).isTrue();
            assertThat(listing.models()).extracting(AiModelInfo::id).containsExactly("chat-1", "pic-1", "old-1");
            assertThat(listing.models().get(0).structuredOutputs()).isTrue();
            assertThat(listing.models().get(0).displayName()).isEqualTo("Chat One");
            assertThat(listing.models().get(1).chatCapable()).isFalse();
            assertThat(listing.models().get(2).retiresAt()).isNotNull();
        }
    }

    @Test
    void aMissingModelRouteMeansListingIsUnsupported() throws Exception {
        try (LocalAiServer server = LocalAiServer.start()) {
            server.json("/v1/models", 404, "{}");

            assertThat(client.listModels(connection("custom", server, null, "m"), deadline()).failure())
                .isEqualTo(AiFailureKind.LIST_UNSUPPORTED);
        }
    }

    @Test
    void openRouterChecksTheKeyBeforeListing() throws Exception {
        try (LocalAiServer server = LocalAiServer.start()) {
            server.json("/v1/key", 401, "{\"error\":{\"code\":401}}").json("/v1/models", 200, "{\"data\":[]}");

            AiModelListing listing = client.listModels(connection("openrouter", server, "bad", "m"), deadline());

            assertThat(listing.failure()).isEqualTo(AiFailureKind.AUTH);
            assertThat(server.requests()).hasSize(1);
        }
    }

    private AiCallFailure failureFor(String response) throws Exception {
        try (LocalAiServer server = LocalAiServer.start()) {
            server.json(CHAT, 200, response);
            AiOutcome outcome = client.complete(connection("openai", server, KEY, "m"), REQUEST,
                new AiRequestShape(OutputMode.SCHEMA, TokenField.MAX_COMPLETION_TOKENS, false), deadline());
            return ((AiOutcome.Failed) outcome).failure();
        }
    }

    private static String answer(String finishReason, String content) {
        return "{\"choices\":[{\"finish_reason\":\"" + finishReason + "\",\"message\":{\"content\":" + quoted(content) + "}}]}";
    }

    private static String quoted(String content) {
        return "\"" + content.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
