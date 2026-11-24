package gg.modl.backend.ai.protocol;

import static gg.modl.backend.ai.protocol.ProtocolFixtures.KEY;
import static gg.modl.backend.ai.protocol.ProtocolFixtures.REQUEST;
import static gg.modl.backend.ai.protocol.ProtocolFixtures.connection;
import static gg.modl.backend.ai.protocol.ProtocolFixtures.deadline;
import static gg.modl.backend.ai.protocol.ProtocolFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.ai.http.LocalAiServer;
import gg.modl.backend.ai.model.AiFailureKind;
import gg.modl.backend.ai.model.AiModelInfo;
import gg.modl.backend.ai.model.AiModelListing;
import gg.modl.backend.ai.model.AiOutcome;
import gg.modl.backend.ai.model.AiRequestShape;
import gg.modl.backend.ai.model.AiRequestShape.OutputMode;
import gg.modl.backend.ai.model.AiRequestShape.TokenField;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

class AnthropicMessagesClientTest {
    private static final String MESSAGES = "/v1/messages";
    private final AnthropicMessagesClient client = new AnthropicMessagesClient(ProtocolFixtures.call());

    @Test
    void theRequestIsExact() throws Exception {
        try (LocalAiServer server = LocalAiServer.start()) {
            server.json(MESSAGES, 200, answer("end_turn"));

            AiOutcome outcome = client.complete(connection("anthropic", server, KEY, "claude-x"), REQUEST,
                new AiRequestShape(OutputMode.SCHEMA, TokenField.MAX_TOKENS, true), deadline());

            assertThat(((AiOutcome.Completed) outcome).text()).isEqualTo("{\"a\":1}");
            LocalAiServer.Recorded sent = server.requests().getFirst();
            assertThat(sent.header("x-api-key")).isEqualTo(KEY);
            assertThat(sent.header("anthropic-version")).isEqualTo(AnthropicMessagesClient.API_VERSION);
            assertThat(sent.header("Authorization")).isNull();
            JsonNode body = json(sent.body());
            assertThat(body.path("max_tokens").intValue()).isEqualTo(4096);
            assertThat(body.path("system").asString()).isEqualTo("system text JSON");
            assertThat(body.path("output_config").path("effort").asString()).isEqualTo("low");
            assertThat(body.path("output_config").path("format").path("type").asString()).isEqualTo("json_schema");
            assertThat(body.has("temperature") || body.has("thinking") || body.has("top_p")).isFalse();
        }
    }

    @Test
    void promptOnlyWithoutAHintOmitsTheOutputConfig() throws Exception {
        try (LocalAiServer server = LocalAiServer.start()) {
            server.json(MESSAGES, 200, answer("end_turn"));

            client.complete(connection("anthropic", server, KEY, "claude-x"), REQUEST,
                new AiRequestShape(OutputMode.PROMPT_ONLY, TokenField.MAX_TOKENS, false), deadline());

            assertThat(json(server.requests().getFirst().body()).has("output_config")).isFalse();
        }
    }

    @ParameterizedTest
    @CsvSource({"max_tokens, TRUNCATED", "model_context_window_exceeded, TRUNCATED", "refusal, REFUSED", "pause_turn, INVALID_RESPONSE",
        "tool_use, INVALID_RESPONSE"})
    void stopReasonsAreReadBeforeContent(String stopReason, AiFailureKind kind) throws Exception {
        try (LocalAiServer server = LocalAiServer.start()) {
            server.json(MESSAGES, 200, answer(stopReason));

            AiOutcome outcome = client.complete(connection("anthropic", server, KEY, "claude-x"), REQUEST,
                new AiRequestShape(OutputMode.SCHEMA, TokenField.MAX_TOKENS, false), deadline());

            assertThat(((AiOutcome.Failed) outcome).failure().kind()).isEqualTo(kind);
        }
    }

    @Test
    void listingFollowsPagesAndSkipsRetiredModels() throws Exception {
        AtomicInteger pages = new AtomicInteger();
        try (LocalAiServer server = LocalAiServer.start()) {
            server.on("/v1/models", exchange -> LocalAiServer.respond(exchange, 200, pages.getAndIncrement() == 0
                ? "{\"data\":[{\"id\":\"a\",\"display_name\":\"A\",\"capabilities\":{\"structured_outputs\":{\"supported\":true},"
                    + "\"effort\":{\"low\":{\"supported\":false}}}},{\"id\":\"gone\",\"lifecycle\":\"retired\"}],\"has_more\":true,\"last_id\":\"gone\"}"
                : "{\"data\":[{\"id\":\"b\",\"lifecycle\":\"deprecated\",\"retires_at\":\"2027-03-01T00:00:00Z\",\"capabilities\":null}],"
                    + "\"has_more\":false}"));

            AiModelListing listing = client.listModels(connection("anthropic", server, KEY, "a"), deadline());

            assertThat(listing.models()).extracting(AiModelInfo::id).containsExactly("a", "b");
            assertThat(listing.models().get(0).structuredOutputs()).isTrue();
            assertThat(listing.models().get(0).effortHint()).isFalse();
            assertThat(listing.models().get(1).deprecated()).isTrue();
            assertThat(listing.models().get(1).retiresAt()).isNotNull();
            assertThat(server.requests().get(1).uri().getQuery()).contains("after_id=gone");
        }
    }

    private static String answer(String stopReason) {
        return "{\"stop_reason\":\"" + stopReason + "\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"x\"},"
            + "{\"type\":\"text\",\"text\":\"{\\\"a\\\":\"},{\"type\":\"text\",\"text\":\"1}\"}]}";
    }
}
