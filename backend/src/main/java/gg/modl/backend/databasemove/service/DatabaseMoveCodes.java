package gg.modl.backend.databasemove.service;

public final class DatabaseMoveCodes {
    public static final String OK = "OK";
    public static final String INVALID = "DATABASE_INVALID";
    public static final String SAME = "DATABASE_SAME";
    public static final String UNREACHABLE = "DATABASE_UNREACHABLE";
    public static final String SIGN_IN_FAILED = "DATABASE_SIGN_IN_FAILED";
    public static final String VERSION_UNSUPPORTED = "DATABASE_VERSION_UNSUPPORTED";
    public static final String PRIVILEGES_MISSING = "DATABASE_PRIVILEGES_MISSING";
    public static final String IN_USE = "DATABASE_IN_USE";
    public static final String HOLDS_OTHER_DATA = "DATABASE_HOLDS_OTHER_DATA";
    public static final String FAILED = "DATABASE_FAILED";
    public static final String WRITES_BUSY = "DATABASE_WRITES_BUSY";
    public static final String COPY_FAILED = "DATABASE_COPY_FAILED";
    public static final String MANAGED = "DATABASE_MANAGED_BY_CONFIG";
    public static final String MOVE_RUNNING = "DATABASE_MOVE_RUNNING";
    public static final String STORAGE_MOVING = "DATABASE_STORAGE_MOVING";
    public static final String IMPORT_RUNNING = "DATABASE_IMPORT_RUNNING";
    public static final String RESTART_NOT_ACKNOWLEDGED = "DATABASE_RESTART_NOT_ACKNOWLEDGED";

    private DatabaseMoveCodes() {
    }
}
