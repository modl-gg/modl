package gg.modl.backend.database.backup;

import gg.modl.backend.database.DatabaseDialect;

public enum ArchiveKind {
    LOGICAL,
    SNAPSHOT;

    public static ArchiveKind preferredFor(DatabaseDialect dialect) {
        return dialect.isExternal() ? LOGICAL : SNAPSHOT;
    }
}
