package gg.modl.minecraft.core.imports.source;

public enum ImportProblem {
    SOURCE_MISSING("import.source_missing"),
    LITEBANS_MISSING("import.litebans_missing"),
    SOURCE_UNREADABLE("import.database_failed"),
    SOURCE_RECONNECT_FAILED("import.source_reconnect_failed"),
    SOURCE_LOCKED("import.source_locked"),
    NO_SPACE("import.no_space"),
    TOO_MANY_PLAYERS("import.too_many_players"),
    EVERY_PLAYER_FAILED("import.every_player_failed"),
    NOTHING_LEFT("import.nothing_left"),
    FILE_FAILED("import.file_failed"),
    UPLOAD_BLOCKED("import.upload_blocked"),
    UPLOAD_FAILED("import.upload_failed"),
    UNREACHABLE("import.unreachable"),
    REQUEST_FAILED("import.request_failed");

    private final String messageKey;

    ImportProblem(String messageKey) {
        this.messageKey = messageKey;
    }

    public String messageKey() {
        return messageKey;
    }
}
