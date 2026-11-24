package gg.modl.backend.ai.model;

public sealed interface AiOutcome {
    AiRequestShape shape();

    record Completed(String text, AiRequestShape shape) implements AiOutcome {
    }

    record Failed(AiCallFailure failure, AiRequestShape shape) implements AiOutcome {
    }
}
