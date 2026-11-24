package gg.modl.backend.database.sql;

import java.util.List;
import java.util.Optional;

public record TargetInspection(
    String serverVersion,
    Optional<String> unsupportedVersion,
    List<String> missingPrivileges,
    List<DurabilityWarning> durabilityWarnings,
    Contents contents
) {
    public enum Contents {
        EMPTY,
        UNFINISHED_COPY,
        COPY_OF_THIS_INSTALLATION,
        OTHER_DATA
    }

    public TargetInspection {
        missingPrivileges = List.copyOf(missingPrivileges);
        durabilityWarnings = List.copyOf(durabilityWarnings);
    }
}
