package gg.modl.backend.database.selection;

import gg.modl.backend.database.sql.PendingTargetCheck;
import gg.modl.backend.database.sql.SqlDatabaseProperties;

@FunctionalInterface
public interface PendingTargetProbe {
    PendingTargetCheck.Result check(SqlDatabaseProperties target, String moveToken);
}
