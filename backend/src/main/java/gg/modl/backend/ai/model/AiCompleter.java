package gg.modl.backend.ai.model;

public interface AiCompleter {
    AiOutcome complete(AiConnection connection, AiCompletionRequest request, AiDeadline deadline, AiAttemptGate gate);
}
