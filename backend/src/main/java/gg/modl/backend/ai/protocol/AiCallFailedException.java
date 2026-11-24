package gg.modl.backend.ai.protocol;

import gg.modl.backend.ai.model.AiCallFailure;
import gg.modl.backend.ai.model.AiFailureKind;

final class AiCallFailedException extends Exception {
    private final transient AiCallFailure failure;

    AiCallFailedException(AiCallFailure failure) {
        super(failure.kind().name(), null, false, false);
        this.failure = failure;
    }

    AiCallFailedException(AiFailureKind kind) {
        this(AiCallFailure.of(kind));
    }

    AiCallFailure failure() {
        return failure;
    }
}
