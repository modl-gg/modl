package gg.modl.backend.database.sql;

import gg.modl.backend.backup.FileTrees;
import gg.modl.backend.database.DatabaseDialect;
import org.jspecify.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

final class MigratedSchemas {
    private static final String WORK_PREFIX = "modl-schema-";
    private static final String SCRATCH_FILE = "schema.db";
    private static final Duration ABANDONED_AFTER = Duration.ofHours(1);
    private static final Pattern FOREIGN_KEY = Pattern.compile("(?i)CONSTRAINT\\s+(\\w+)\\s+FOREIGN\\s+KEY");
    private static final String SCHEMA_OBJECTS = "SELECT type, name, tbl_name, COALESCE(sql, '') FROM sqlite_master "
        + "WHERE name NOT LIKE 'sqlite_%'";

    private final DatabaseAccess access;
    private final Map<String, MigratedSchema> byVersion = new ConcurrentHashMap<>();
    private volatile @Nullable String latestVersion;

    MigratedSchemas(DatabaseAccess access) {
        this.access = access;
    }

    String latestVersion() {
        String known = latestVersion;
        if (known == null) {
            known = inScratchDatabase(scratch -> ModlSchema.latestVersion(access, DatabaseDialect.SQLITE, scratch));
            latestVersion = known;
        }
        return known;
    }

    boolean isNewerThanThisJar(String version) {
        return ModlSchema.isNewer(version, latestVersion());
    }

    void requireSupported(String version) {
        ModlSchema.requireSupported(version, latestVersion());
    }

    MigratedSchema latest() {
        return through(latestVersion());
    }

    MigratedSchema through(String version) {
        MigratedSchema known = byVersion.get(version);
        if (known != null) {
            return known;
        }
        requireSupported(version);
        MigratedSchema migrated = inScratchDatabase(scratch -> migrate(scratch, version));
        byVersion.put(version, migrated);
        return migrated;
    }

    private MigratedSchema migrate(DataSource scratch, String version) {
        access.flyway(DatabaseDialect.SQLITE, scratch).target(version).load().migrate();
        Set<String> tables = new HashSet<>();
        Set<String> indexes = new HashSet<>();
        Set<String> foreignKeys = new HashSet<>();
        try (Connection connection = scratch.getConnection()) {
            for (List<String> object : access.statements().rows(connection, SCHEMA_OBJECTS)) {
                if (ModlSchema.HISTORY_TABLE.equals(object.get(2))) {
                    continue;
                }
                switch (object.get(0)) {
                    case "table" -> {
                        tables.add(object.get(1));
                        Matcher constraint = FOREIGN_KEY.matcher(object.get(3));
                        while (constraint.find()) {
                            foreignKeys.add(constraint.group(1));
                        }
                    }
                    case "index" -> indexes.add(object.get(1));
                    default -> throw new DatabaseToolException("The modl migrations create a " + object.get(0) + " ("
                        + object.get(1) + "), which backups do not support");
                }
            }
        } catch (SQLException e) {
            throw DatabaseToolException.failedTo("read the tables of the modl migrations", e);
        }
        return new MigratedSchema(version, tables, indexes, foreignKeys);
    }

    private <T> T inScratchDatabase(ScratchWork<T> work) {
        access.prepareSqlite();
        Path root = access.sqliteTemporaryDirectory();
        try {
            Path directory = Files.createTempDirectory(root, WORK_PREFIX);
            try {
                removeAbandoned(root, Files.getLastModifiedTime(directory).toInstant().minus(ABANDONED_AFTER));
                SqliteConnections connections = access.sqliteConnections(directory.resolve(SCRATCH_FILE));
                return work.run(access.tracked(connections.migration()));
            } finally {
                FileTrees.deleteRecursively(directory);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not use a scratch database in " + root, e);
        }
    }

    private static void removeAbandoned(Path root, Instant before) throws IOException {
        for (Path child : FileTrees.children(root)) {
            if (child.getFileName().toString().startsWith(WORK_PREFIX) && FileTrees.modifiedBefore(child, before)) {
                FileTrees.deleteRecursively(child);
            }
        }
    }

    @FunctionalInterface
    private interface ScratchWork<T> {
        T run(DataSource scratch);
    }
}
