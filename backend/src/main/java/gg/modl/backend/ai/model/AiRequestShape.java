package gg.modl.backend.ai.model;

public record AiRequestShape(OutputMode outputMode, TokenField tokenField, boolean effortHint) {
    public AiRequestShape withOutputMode(OutputMode mode) {
        return new AiRequestShape(mode, tokenField, effortHint);
    }

    public AiRequestShape withSwappedTokenField() {
        return new AiRequestShape(outputMode, tokenField.swapped(), effortHint);
    }

    public AiRequestShape withoutEffortHint() {
        return new AiRequestShape(outputMode, tokenField, false);
    }

    public enum OutputMode {
        SCHEMA,
        JSON_OBJECT,
        PROMPT_ONLY
    }

    public enum TokenField {
        MAX_COMPLETION_TOKENS("max_completion_tokens"),
        MAX_TOKENS("max_tokens");

        private final String jsonName;

        TokenField(String jsonName) {
            this.jsonName = jsonName;
        }

        public String jsonName() {
            return jsonName;
        }

        public TokenField swapped() {
            return this == MAX_TOKENS ? MAX_COMPLETION_TOKENS : MAX_TOKENS;
        }
    }
}
