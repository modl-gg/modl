package gg.modl.backend.ai.model;

public record AiCompletionRequest(String system, String user, AiOutputSchema schema, int maxOutputTokens) {
}
