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

class GeminiGenerateContentClientTest {
    private final GeminiGenerateContentClient client = new GeminiGenerateContentClient(ProtocolFixtures.call());

    @Test
    void theRequestUsesTheHeaderKeyAndResponseFormat() throws Exception {
        try (LocalAiServer server = LocalAiServer.start()) {
            server.json("/v1/models/gem-1:generateContent", 200, answer("STOP"));

            AiOutcome outcome = client.complete(connection("gemini", server, KEY, "models/gem-1"), REQUEST,
                new AiRequestShape(OutputMode.SCHEMA, TokenField.MAX_TOKENS, false), deadline());

            assertThat(((AiOutcome.Completed) outcome).text()).isEqualTo("{\"verdict\":1}");
            LocalAiServer.Recorded sent = server.requests().getFirst();
            assertThat(sent.header("x-goog-api-key")).isEqualTo(KEY);
            assertThat(sent.uri().getQuery()).isNull();
            JsonNode body = json(sent.body());
            assertThat(body.path("systemInstruction").path("parts").get(0).path("text").asString()).isEqualTo("system text JSON");
            JsonNode format = body.path("generationConfig").path("responseFormat").path("text");
            assertThat(format.path("mimeType").asString()).isEqualTo("APPLICATION_JSON");
            assertThat(format.has("schema")).isTrue();
            assertThat(body.path("generationConfig").path("maxOutputTokens").intValue()).isEqualTo(4096);
            assertThat(body.path("generationConfig").has("responseSchema")).isFalse();
        }
    }

    @Test
    void anUnsafeModelIdIsNeverPutInThePath() throws Exception {
        try (LocalAiServer server = LocalAiServer.start()) {
            AiOutcome outcome = client.complete(connection("gemini", server, KEY, "../evil"), REQUEST,
                new AiRequestShape(OutputMode.SCHEMA, TokenField.MAX_TOKENS, false), deadline());

            assertThat(((AiOutcome.Failed) outcome).failure().kind()).isEqualTo(AiFailureKind.INVALID_REQUEST);
            assertThat(server.requests()).isEmpty();
        }
    }

    @ParameterizedTest
    @CsvSource({"MAX_TOKENS, TRUNCATED", "CONTINUATION, TRUNCATED", "SAFETY, FILTERED", "SPII, FILTERED",
        "PUP_LIMITED_DISABLED, PERMISSION", "OTHER, INVALID_RESPONSE"})
    void finishReasonsMapToFailures(String finishReason, AiFailureKind kind) throws Exception {
        try (LocalAiServer server = LocalAiServer.start()) {
            server.json("/v1/models/gem-1:generateContent", 200, answer(finishReason));

            AiOutcome outcome = client.complete(connection("gemini", server, KEY, "gem-1"), REQUEST,
                new AiRequestShape(OutputMode.JSON_OBJECT, TokenField.MAX_TOKENS, false), deadline());

            assertThat(((AiOutcome.Failed) outcome).failure().kind()).isEqualTo(kind);
        }
    }

    @Test
    void aBlockedPromptIsFiltered() throws Exception {
        try (LocalAiServer server = LocalAiServer.start()) {
            server.json("/v1/models/gem-1:generateContent", 200, "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}");

            AiOutcome outcome = client.complete(connection("gemini", server, KEY, "gem-1"), REQUEST,
                new AiRequestShape(OutputMode.PROMPT_ONLY, TokenField.MAX_TOKENS, false), deadline());

            assertThat(((AiOutcome.Failed) outcome).failure().kind()).isEqualTo(AiFailureKind.FILTERED);
            assertThat(json(server.requests().getFirst().body()).path("generationConfig").has("responseFormat")).isFalse();
        }
    }

    @Test
    void listingFollowsPageTokens() throws Exception {
        AtomicInteger pages = new AtomicInteger();
        try (LocalAiServer server = LocalAiServer.start()) {
            server.on("/v1/models", exchange -> LocalAiServer.respond(exchange, 200, pages.getAndIncrement() == 0
                ? "{\"models\":[{\"name\":\"models/gem-1\",\"supportedGenerationMethods\":[\"generateContent\"]}],\"nextPageToken\":\"p2\"}"
                : "{\"models\":[{\"name\":\"models/embed-1\",\"supportedGenerationMethods\":[\"embedContent\"]}]}"));

            AiModelListing listing = client.listModels(connection("gemini", server, KEY, "gem-1"), deadline());

            assertThat(listing.models()).extracting(AiModelInfo::id).containsExactly("gem-1", "embed-1");
            assertThat(listing.models()).extracting(AiModelInfo::chatCapable).containsExactly(true, false);
            assertThat(server.requests().get(1).uri().getQuery()).contains("pageToken=p2");
        }
    }

    private static String answer(String finishReason) {
        return "{\"candidates\":[{\"finishReason\":\"" + finishReason + "\",\"content\":{\"parts\":["
            + "{\"text\":\"thinking\",\"thought\":true},{\"text\":\"{\\\"verdict\\\":1}\"}]}}]}";
    }
}
