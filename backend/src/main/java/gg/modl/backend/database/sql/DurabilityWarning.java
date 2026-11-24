package gg.modl.backend.database.sql;

public record DurabilityWarning(Kind kind, String setting, String value, String expected) {
    public enum Kind {
        SETTING,
        NOT_STRICT,
        JOURNAL_MODE,
        SYNCHRONOUS
    }

    static DurabilityWarning setting(String setting, String value, String expected) {
        return new DurabilityWarning(Kind.SETTING, setting, value, expected);
    }

    static DurabilityWarning notStrict() {
        return new DurabilityWarning(Kind.NOT_STRICT, "", "", "");
    }

    static DurabilityWarning journalMode(String value) {
        return new DurabilityWarning(Kind.JOURNAL_MODE, "journal_mode", value, "wal");
    }

    static DurabilityWarning synchronous(String value) {
        return new DurabilityWarning(Kind.SYNCHRONOUS, "synchronous", value, "2");
    }

    public String describe() {
        return switch (kind) {
            case SETTING -> "The database setting " + setting + " is " + value + "; set it to " + expected
                + " so a crash cannot lose acknowledged moderation actions.";
            case NOT_STRICT -> "The database session is not in strict SQL mode, so it could silently shorten or change values.";
            case JOURNAL_MODE -> "The database is in " + value + " journal mode instead of WAL; restart modl to repair it.";
            case SYNCHRONOUS -> "The database is not syncing every commit to disk (synchronous=" + value + ").";
        };
    }
}
