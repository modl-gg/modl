package gg.modl.backend.ai.service;

import gg.modl.backend.ai.data.AIAnalysisResult;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class AiAnalysisResponseParser {
    static final int MAX_ANALYSIS_CODE_POINTS = 500;
    static final int MAX_RAW_RESPONSE = 8192;

    private static final JsonMapper STRICT = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();
    private static final String THINK_OPEN = "<think>";
    private static final String THINK_CLOSE = "</think>";
    private static final String FENCE = "```";
    private static final Pattern COLOUR_CODES = Pattern.compile("[§&][0-9A-FK-ORXa-fk-orx]");
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}  ]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public Optional<AIAnalysisResult> parse(String text, Collection<String> allowedTypeIds, Instant now) {
        Optional<String> json = unwrapped(text);
        if (json.isEmpty()) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = STRICT.readTree(json.get());
        } catch (JacksonException malformed) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        String analysis = sanitisedAnalysis(root.path("analysis"));
        Double confidence = confidence(root.path("confidence"));
        String typeId = typeId(root.path("punishmentTypeId"));
        String severity = severity(root.path("severity"));
        if (analysis == null || confidence == null || typeId == null || severity == null) {
            return Optional.empty();
        }
        boolean noAction = ChatModerationSchema.NONE.equals(typeId);
        if (!noAction && (ChatModerationSchema.NONE.equals(severity) || !allowedTypeIds.contains(typeId))) {
            return Optional.empty();
        }
        AIAnalysisResult.SuggestedAction suggestion = noAction ? null
            : new AIAnalysisResult.SuggestedAction(Integer.parseInt(typeId), severity);
        AIAnalysisResult result = new AIAnalysisResult(analysis, suggestion, now, capped(STRICT.writeValueAsString(root)));
        result.setConfidence(confidence);
        return Optional.of(result);
    }

    private static Optional<String> unwrapped(String text) {
        String remaining = text.strip();
        if (remaining.startsWith(THINK_OPEN)) {
            int close = remaining.indexOf(THINK_CLOSE);
            if (close < 0) {
                return Optional.empty();
            }
            remaining = remaining.substring(close + THINK_CLOSE.length()).strip();
        }
        if (remaining.startsWith(FENCE)) {
            int firstLineEnd = remaining.indexOf('\n');
            if (firstLineEnd < 0 || !remaining.endsWith(FENCE) || remaining.length() < firstLineEnd + FENCE.length()) {
                return Optional.empty();
            }
            remaining = remaining.substring(firstLineEnd + 1, remaining.length() - FENCE.length()).strip();
        }
        return remaining.isEmpty() ? Optional.empty() : Optional.of(remaining);
    }

    @Nullable
    private static String sanitisedAnalysis(JsonNode node) {
        if (!node.isString()) {
            return null;
        }
        String cleaned = COLOUR_CODES.matcher(node.stringValue()).replaceAll("");
        cleaned = INVISIBLE.matcher(cleaned).replaceAll(" ");
        cleaned = WHITESPACE.matcher(cleaned).replaceAll(" ").strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.codePointCount(0, cleaned.length()) > MAX_ANALYSIS_CODE_POINTS) {
            cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_ANALYSIS_CODE_POINTS));
        }
        return cleaned;
    }

    @Nullable
    private static Double confidence(JsonNode node) {
        if (!node.isNumber()) {
            return null;
        }
        double value = node.doubleValue();
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0 ? value : null;
    }

    @Nullable
    private static String typeId(JsonNode node) {
        if (node.isString()) {
            String value = node.stringValue().strip();
            if (value.equalsIgnoreCase(ChatModerationSchema.NONE)) {
                return ChatModerationSchema.NONE;
            }
            return value.matches("\\d{1,9}") ? String.valueOf(Integer.parseInt(value)) : null;
        }
        if (node.isIntegralNumber() && node.canConvertToInt() && node.intValue() >= 0) {
            return String.valueOf(node.intValue());
        }
        return null;
    }

    @Nullable
    private static String severity(JsonNode node) {
        if (!node.isString()) {
            return null;
        }
        String value = node.stringValue().strip().toLowerCase(Locale.ROOT);
        return ChatModerationSchema.SEVERITIES.contains(value) ? value : null;
    }

    private static String capped(String raw) {
        return raw.length() <= MAX_RAW_RESPONSE ? raw : raw.substring(0, MAX_RAW_RESPONSE);
    }
}
