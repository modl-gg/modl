package gg.modl.backend.databasemove.service;

import java.util.List;

public record DatabaseTestResult(
    boolean ok,
    String code,
    String message,
    String detail,
    String serverVersion,
    List<TargetWarning> warnings,
    boolean replacesCopy
) {
    public DatabaseTestResult {
        warnings = List.copyOf(warnings);
    }

    static DatabaseTestResult passed(String serverVersion, List<TargetWarning> warnings, boolean replacesCopy) {
        return new DatabaseTestResult(true, DatabaseMoveCodes.OK, "The database is ready", "", serverVersion, warnings, replacesCopy);
    }

    static DatabaseTestResult refused(String code, String message, String detail) {
        return new DatabaseTestResult(false, code, message, detail, "", List.of(), false);
    }
}
