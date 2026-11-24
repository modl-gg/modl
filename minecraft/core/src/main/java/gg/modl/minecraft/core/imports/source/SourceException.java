package gg.modl.minecraft.core.imports.source;

import lombok.Getter;

@Getter
public final class SourceException extends Exception {
    private static final String MISSING = "import.source_missing";
    private static final String FAILED = "import.database_failed";
    private static final String LOCKED = "import.source_locked";
    private static final String NO_SPACE = "import.no_space";
    private static final String TOO_LARGE = "import.too_many_players";

    private final String messageKey;

    private SourceException(String messageKey, String detail, Throwable cause) {
        super(detail, cause);
        this.messageKey = messageKey;
    }

    private SourceException(String messageKey, String detail) {
        super(detail);
        this.messageKey = messageKey;
    }

    public static SourceException missing(String messageKey, String detail) {
        return new SourceException(messageKey, detail);
    }

    public static SourceException missing(String detail) {
        return new SourceException(MISSING, detail);
    }

    public static SourceException failed(Throwable cause) {
        return new SourceException(FAILED, cause.toString(), cause);
    }

    public static SourceException failed(String detail) {
        return new SourceException(FAILED, detail);
    }

    public static SourceException noSpace(String detail) {
        return new SourceException(NO_SPACE, detail);
    }

    public static SourceException tooLarge(String detail) {
        return new SourceException(TOO_LARGE, detail);
    }

    public static SourceException locked(String detail) {
        return new SourceException(LOCKED, detail);
    }
}
