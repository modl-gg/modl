package gg.modl.backend.migration.data;

public enum ImportEnding {
    CANCELLED(true),
    TIMED_OUT(false),
    INTERRUPTED(true),
    EXPORT_FAILED(true),
    UPLOAD_FAILED(true),
    FILE_TOO_LARGE(true),
    INVALID_FILE(false),
    IMPORT_FAILED(false);

    private final boolean reportedByPlugin;

    ImportEnding(boolean reportedByPlugin) {
        this.reportedByPlugin = reportedByPlugin;
    }

    public boolean mayBeReportedByPlugin() {
        return reportedByPlugin;
    }

    public boolean endsAcceptedImport() {
        return this == CANCELLED || !reportedByPlugin;
    }
}
