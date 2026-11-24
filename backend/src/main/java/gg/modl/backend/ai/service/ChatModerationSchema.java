package gg.modl.backend.ai.service;

import gg.modl.backend.ai.model.AiOutputSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ChatModerationSchema {
    public static final String NAME = "chat_moderation";
    public static final String NONE = "none";
    public static final List<String> SEVERITIES = List.of(NONE, "lenient", "regular", "severe");
    public static final List<String> FIELDS = List.of("analysis", "confidence", "punishmentTypeId", "severity");

    private ChatModerationSchema() {
    }

    public static AiOutputSchema forTypes(List<String> sortedTypeIds) {
        List<String> typeIds = new ArrayList<>(sortedTypeIds);
        typeIds.add(NONE);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("analysis", property("string", "A brief explanation, at most two sentences."));
        properties.put("confidence", property("number", "Confidence in the verdict, from 0.0 to 1.0."));
        Map<String, Object> type = property("string", "A rule ID, or none when no action is needed.");
        type.put("enum", List.copyOf(typeIds));
        properties.put("punishmentTypeId", type);
        Map<String, Object> severity = new LinkedHashMap<>();
        severity.put("type", "string");
        severity.put("enum", SEVERITIES);
        properties.put("severity", severity);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("required", FIELDS);
        schema.put("properties", properties);
        return new AiOutputSchema(NAME, schema);
    }

    private static Map<String, Object> property(String type, String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", type);
        property.put("description", description);
        return property;
    }
}
