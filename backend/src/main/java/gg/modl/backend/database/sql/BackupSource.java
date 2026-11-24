package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseDialect;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;

public interface BackupSource {
    DatabaseDialect dialect();

    boolean exists();

    long sizeBytes();

    void requireServing();

    <T> T readConsistently(ViewReader<T> reader) throws IOException;

    Optional<SqliteSnapshots> snapshots();

    Set<String> tablesAnUpgradeCreates();
}
