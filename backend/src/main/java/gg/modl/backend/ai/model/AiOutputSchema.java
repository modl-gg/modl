package gg.modl.backend.ai.model;

import java.util.Map;

public record AiOutputSchema(String name, Map<String, Object> schema) {
}
