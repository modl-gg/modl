package gg.modl.backend.ai.model;

import java.util.Optional;

@FunctionalInterface
public interface AiAttemptGate {
    AiAttemptGate UNMETERED = Optional::empty;

    Optional<AiFailureKind> admit();
}
