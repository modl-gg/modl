package gg.modl.minecraft.core.embedded;

import org.jspecify.annotations.Nullable;

public final class EmbeddedServerException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public EmbeddedServerException(String message) {
        super(message);
    }

    public EmbeddedServerException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
