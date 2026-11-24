package gg.modl.backend.ai.http;

import gg.modl.backend.ai.model.AiFailureKind;

public class AiTransportException extends Exception {
    private final AiFailureKind kind;

    public AiTransportException(AiFailureKind kind) {
        super(kind.name(), null, false, false);
        this.kind = kind;
    }

    public AiFailureKind kind() {
        return kind;
    }
}
