package gg.modl.backend.database.sql;

import static gg.modl.backend.database.jooq.Tables.INSTANCE_SECRETS;
import static gg.modl.backend.database.jooq.Tables.INSTANCE_STATE;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.DatabaseDialect;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public record PreMigrationState(boolean hasModlTables, @Nullable String lastRunVersion, @Nullable String schemaVersion,
                                String databaseId) {
    private static final String STATE_TABLE = INSTANCE_STATE.getName();
    private static final String INSTANCE_IDENTITY_CONTEXT = "modl database identity\u0000";

    public static PreMigrationState read(DataSource migration, DatabaseDialect dialect, DataDirectoryProperties dataDirectory) {
        try (Connection connection = migration.getConnection()) {
            Set<String> tables = SchemaCatalog.allTableNames(connection, dialect);
            DSLContext dsl = queries(connection, dialect);
            String schema = tables.contains(ModlSchema.HISTORY_TABLE) && tables.contains(INSTANCE_SECRETS.getName())
                ? ModlSchema.currentVersion(dsl).orElse(null)
                : null;
            boolean modlTables = schema != null;
            String lastRun = tables.contains(STATE_TABLE)
                ? dsl.select(INSTANCE_STATE.LAST_RUN_VERSION).from(INSTANCE_STATE).where(INSTANCE_STATE.ID.eq(1))
                    .fetchOptional(INSTANCE_STATE.LAST_RUN_VERSION).orElse(null)
                : null;
            return new PreMigrationState(modlTables, lastRun, schema, databaseId(connection, dialect, dsl, modlTables,
                dataDirectory.database().resolve(SqliteConnections.DATABASE_FILE_NAME)));
        } catch (SQLException e) {
            throw new IllegalStateException("The database could not be read before migrating it", e);
        }
    }

    public static void recordRunningVersion(DataSource migration, DatabaseDialect dialect, String version, Instant now) {
        try (Connection connection = migration.getConnection()) {
            DSLContext dsl = queries(connection, dialect);
            Optional<String> stored = dsl.select(INSTANCE_STATE.LAST_RUN_VERSION).from(INSTANCE_STATE)
                .where(INSTANCE_STATE.ID.eq(1)).fetchOptional(INSTANCE_STATE.LAST_RUN_VERSION);
            if (stored.isEmpty()) {
                dsl.insertInto(INSTANCE_STATE)
                    .set(INSTANCE_STATE.ID, 1)
                    .set(INSTANCE_STATE.LAST_RUN_VERSION, version)
                    .set(INSTANCE_STATE.VERSION_CHANGED_AT, now)
                    .set(INSTANCE_STATE.CHECKS_ENABLED, true)
                    .set(INSTANCE_STATE.INCLUDE_PRERELEASES, false)
                    .set(INSTANCE_STATE.CHECK_FAILURES, 0)
                    .execute();
            } else if (!stored.get().equals(version)) {
                dsl.update(INSTANCE_STATE)
                    .set(INSTANCE_STATE.LAST_RUN_VERSION, version)
                    .set(INSTANCE_STATE.VERSION_CHANGED_AT, now)
                    .where(INSTANCE_STATE.ID.eq(1))
                    .execute();
            }
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("The running modl version could not be recorded", e);
        }
    }

    private static DSLContext queries(Connection connection, DatabaseDialect dialect) {
        return DSL.using(connection, dialect.sqlDialect(), JooqConfiguration.settings());
    }

    private static String databaseId(Connection connection, DatabaseDialect dialect, DSLContext dsl, boolean modlTables, Path sqliteFile)
        throws SQLException {
        Optional<String> instanceKey = modlTables
            ? dsl.select(INSTANCE_SECRETS.STORAGE_SIGNING_KEY).from(INSTANCE_SECRETS).where(INSTANCE_SECRETS.ID.eq(1))
                .fetchOptional(INSTANCE_SECRETS.STORAGE_SIGNING_KEY)
            : Optional.empty();
        if (instanceKey.isPresent()) {
            return "instance:" + sha256(INSTANCE_IDENTITY_CONTEXT + instanceKey.get());
        }
        if (!dialect.isExternal()) {
            return "sqlite:" + realPath(sqliteFile);
        }
        String identity = dialect.name() + "|" + connection.getCatalog() + "|" + connection.getSchema();
        return "sha256:" + sha256(identity);
    }

    private static String realPath(Path file) {
        try {
            return file.toRealPath().toString();
        } catch (IOException e) {
            return file.toAbsolutePath().normalize().toString();
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
