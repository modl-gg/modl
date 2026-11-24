package gg.modl.backend.ai.protocol;

import gg.modl.backend.ai.model.AiShapeRejection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

final class AiShapeRejections {
    private static final List<String> OUTPUT_FORMAT_MARKERS = List.of(
        "response_format", "json_schema", "json_object", "responseformat", "response_schema", "output_config.format",
        "output_format", "structured output", "json mode", "requested parameters");
    private static final List<String> TOKEN_FIELDS = List.of("max_tokens", "max_completion_tokens");
    private static final List<String> TOKEN_FIELD_COMPLAINTS = List.of("unsupported", "unknown", "unrecognized", "deprecated");
    private static final Pattern EFFORT = Pattern.compile("reasoning_effort|output_config\\.effort|\\beffort\\b");

    private AiShapeRejections() {
    }

    @Nullable
    static AiShapeRejection detect(int status, @Nullable JsonNode body) {
        if ((status != 400 && status != 404 && status != 422) || body == null) {
            return null;
        }
        String evidence = evidence(body);
        if (TOKEN_FIELDS.stream().anyMatch(evidence::contains) && TOKEN_FIELD_COMPLAINTS.stream().anyMatch(evidence::contains)) {
            return AiShapeRejection.TOKEN_FIELD;
        }
        if (EFFORT.matcher(evidence).find()) {
            return AiShapeRejection.EFFORT;
        }
        if (OUTPUT_FORMAT_MARKERS.stream().anyMatch(evidence::contains)) {
            return AiShapeRejection.OUTPUT_FORMAT;
        }
        return null;
    }

    private static String evidence(JsonNode body) {
        JsonNode error = body.path("error");
        StringBuilder evidence = new StringBuilder();
        append(evidence, error.isString() ? error.stringValue() : null);
        append(evidence, AiJson.text(error, "param"));
        append(evidence, AiJson.text(error, "message"));
        append(evidence, AiJson.text(body, "message"));
        return evidence.toString().toLowerCase(Locale.ROOT);
    }

    private static void append(StringBuilder evidence, @Nullable String value) {
        if (value != null) {
            evidence.append(value).append('\n');
        }
    }
}
