package gg.modl.backend.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.ai.data.AIAnalysisResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AiAnalysisResponseParserTest {
    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private static final List<String> ALLOWED = List.of("6", "7");
    private final AiAnalysisResponseParser parser = new AiAnalysisResponseParser();

    @Test
    void aWellFormedViolationBecomesASuggestion() {
        AIAnalysisResult result = parse(answer("\"Spam.\"", "0.9", "\"6\"", "\"Severe\"")).orElseThrow();

        assertThat(result.getAnalysis()).isEqualTo("Spam.");
        assertThat(result.getConfidence()).isEqualTo(0.9);
        assertThat(result.getSuggestedAction().getPunishmentTypeId()).isEqualTo(6);
        assertThat(result.getSuggestedAction().getSeverity()).isEqualTo("severe");
        assertThat(result.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void anIntegerTypeIdIsAccepted() {
        assertThat(parse(answer("\"x\"", "0.9", "7", "\"lenient\"")).orElseThrow().getSuggestedAction().getPunishmentTypeId())
            .isEqualTo(7);
    }

    @Test
    void noneMeansNoSuggestion() {
        AIAnalysisResult result = parse(answer("\"Fine.\"", "0.2", "\"none\"", "\"none\"")).orElseThrow();

        assertThat(result.getSuggestedAction()).isNull();
        assertThat(result.hasViolation()).isFalse();
    }

    @Test
    void aThinkBlockAndACodeFenceAreStripped() {
        String fenced = "<think>reasoning that is not JSON</think>\n```json\n" + answer("\"x\"", "0.5", "\"6\"", "\"regular\"") + "\n```";

        assertThat(parse(fenced)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Here you go: {\"analysis\":\"x\",\"confidence\":0.9,\"punishmentTypeId\":\"6\",\"severity\":\"severe\"}",
        "{\"analysis\":\"x\",\"confidence\":0.9,\"punishmentTypeId\":\"6\",\"severity\":\"severe\"} trailing",
        "{\"analysis\":\"x\",\"confidence\":0.9,\"punishmentTypeId\":\"6\",\"severity\":\"severe\"} {\"analysis\":\"y\"}",
        "{\"analysis\":\"x\",\"analysis\":\"y\",\"confidence\":0.9,\"punishmentTypeId\":\"6\",\"severity\":\"severe\"}",
        "<think>never closed {\"analysis\":\"x\"}",
        "[]",
        ""
    })
    void anythingButOneJsonObjectIsInvalid(String text) {
        assertThat(parse(text)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"85", "\"0.9\"", "1.0001", "-0.1", "\"NaN\"", "null"})
    void confidenceMustBeANumberInTheUnitInterval(String confidence) {
        assertThat(parse(answer("\"x\"", confidence, "\"6\"", "\"severe\""))).isEmpty();
    }

    @Test
    void theBoundsOfTheUnitIntervalAreAccepted() {
        assertThat(parse(answer("\"x\"", "0", "\"6\"", "\"severe\""))).isPresent();
        assertThat(parse(answer("\"x\"", "1", "\"6\"", "\"severe\""))).isPresent();
    }

    @Test
    void typesAndSeveritiesAreChecked() {
        assertThat(parse(answer("\"x\"", "0.9", "\"2\"", "\"severe\""))).isEmpty();
        assertThat(parse(answer("\"x\"", "0.9", "\"6\"", "\"none\""))).isEmpty();
        assertThat(parse(answer("\"x\"", "0.9", "\"6\"", "\"extreme\""))).isEmpty();
        assertThat(parse(answer("\"x\"", "0.9", "\"none\"", "\"extreme\""))).isEmpty();
        assertThat(parse("{\"analysis\":\"x\",\"confidence\":0.9,\"punishmentTypeId\":\"6\"}")).isEmpty();
    }

    @Test
    void theAnalysisIsSanitisedAndCapped() {
        String dirty = "\"\\u00a7cRed &lbold\\u202e reversed\\u0007\\n\\n  spaced " + "a".repeat(600) + "\"";

        String analysis = parse(answer(dirty, "0.9", "\"6\"", "\"severe\"")).orElseThrow().getAnalysis();

        assertThat(analysis).startsWith("Red bold reversed spaced a").doesNotContain("§", "\u202e", "\u0007", "\n", "  ");
        assertThat(analysis.codePointCount(0, analysis.length())).isEqualTo(AiAnalysisResponseParser.MAX_ANALYSIS_CODE_POINTS);
        assertThat(parse(answer("\"\\u00a7c \"", "0.9", "\"6\"", "\"severe\""))).isEmpty();
    }

    @Test
    void theRawResponseIsTheCleanedJsonAndIsCapped() {
        AIAnalysisResult result = parse("```\n" + answer("\"" + "b".repeat(400) + "\"", "0.9", "\"6\"", "\"severe\"") + "\n```")
            .orElseThrow();

        assertThat(result.getRawResponse()).startsWith("{\"analysis\"").doesNotContain("```");
        assertThat(result.getRawResponse().length()).isLessThanOrEqualTo(AiAnalysisResponseParser.MAX_RAW_RESPONSE);
    }

    private Optional<AIAnalysisResult> parse(String text) {
        return parser.parse(text, ALLOWED, NOW);
    }

    private static String answer(String analysis, String confidence, String typeId, String severity) {
        return "{\"analysis\":" + analysis + ",\"confidence\":" + confidence + ",\"punishmentTypeId\":" + typeId
            + ",\"severity\":" + severity + "}";
    }
}
