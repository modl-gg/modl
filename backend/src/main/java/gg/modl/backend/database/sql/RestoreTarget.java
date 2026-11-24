package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseDialect;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public interface RestoreTarget {
    DatabaseDialect dialect();

    Path workDirectory();

    SqliteFile stagingFile(Path file);

    List<String> modlTables();

    boolean holdsUnfinishedRestore();

    void requireReplaceable(SchemaCatalog replacement, Set<String> newerVersionTables);

    void replaceWith(SqliteFile verified, Set<String> newerVersionTables) throws IOException;

    void upgradeSchema(Instant now);
}
