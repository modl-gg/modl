package gg.modl.update;

import java.io.IOException;

public final class ArtifactMismatchException extends IOException {
    private static final long serialVersionUID = 1L;

    public ArtifactMismatchException(String message) {
        super(message);
    }
}
