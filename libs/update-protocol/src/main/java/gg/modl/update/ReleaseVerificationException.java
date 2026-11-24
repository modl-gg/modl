package gg.modl.update;

import org.jspecify.annotations.Nullable;

public final class ReleaseVerificationException extends Exception {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        INVALID_SIGNATURE,
        INVALID_MANIFEST,
        UNSUPPORTED_FORMAT
    }

    private final Reason reason;

    public ReleaseVerificationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ReleaseVerificationException(Reason reason, @Nullable String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
