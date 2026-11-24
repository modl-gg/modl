package gg.modl.backend.database.sql;

import gg.modl.backend.database.DataDirectoryProperties;
import java.nio.file.Path;

final class SqliteLocations {
    private static final String TEMPORARY_DIRECTORY = "sqlite";

    private SqliteLocations() {
    }

    static Path databaseFile(DataDirectoryProperties dataDirectory) {
        return dataDirectory.database().resolve(SqliteConnections.DATABASE_FILE_NAME);
    }

    static Path temporaryDirectory(DataDirectoryProperties dataDirectory) {
        return dataDirectory.temporary().resolve(TEMPORARY_DIRECTORY);
    }

    static Path nativeParent(SqlDatabaseProperties database, DataDirectoryProperties dataDirectory) {
        return database.sqlite().configuredNativeDir().map(Path::of).orElse(temporaryDirectory(dataDirectory));
    }
}
