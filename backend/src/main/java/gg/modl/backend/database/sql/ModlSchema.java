package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseDialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

final class ModlSchema {
    static final String HISTORY_TABLE = "modl_schema_history";
    static final String BEFORE_FIRST_MIGRATION = "0";
    static final String UNFINISHED_TABLE = "modl_unfinished_restore";
    static final String RESTORE_TABLES = "modl_restore_tables";

    private static final String UNCREATED_HISTORY = "modl_migration_probe";
    private static final Table<Record> HISTORY = DSL.table(DSL.name(HISTORY_TABLE));
    private static final Field<String> VERSION = DSL.field(DSL.name("version"), SQLDataType.VARCHAR);
    private static final Field<Boolean> SUCCESS = DSL.field(DSL.name("success"), SQLDataType.BOOLEAN);
    private static final Field<Integer> CHECKSUM = DSL.field(DSL.name("checksum"), SQLDataType.INTEGER);
    private static final Set<String> HISTORY_COLUMNS = Set.of("version", "success", "checksum");

    private ModlSchema() {
    }

    static boolean isModlHistory(DatabaseAccess access, Connection connection, DatabaseDialect dialect, DataSource connections)
        throws SQLException {
        if (!SchemaCatalog.columnNames(connection, dialect, HISTORY_TABLE).containsAll(HISTORY_COLUMNS)) {
            return false;
        }
        MigrationInfo first = resolved(access, dialect, connections)
            .min(Comparator.comparing(MigrationInfo::getVersion))
            .orElseThrow(ModlSchema::noMigrations);
        return access.queries(connection, dialect).fetchExists(HISTORY, VERSION.eq(first.getVersion().getVersion())
            .and(SUCCESS.eq(true))
            .and(CHECKSUM.eq(first.getChecksum())));
    }

    static Optional<String> currentVersion(DSLContext dsl) {
        return dsl.select(VERSION)
            .from(HISTORY)
            .where(VERSION.isNotNull())
            .and(SUCCESS.eq(true))
            .fetch(VERSION)
            .stream()
            .map(MigrationVersion::fromVersion)
            .max(Comparator.naturalOrder())
            .map(MigrationVersion::getVersion);
    }

    static String latestVersion(DatabaseAccess access, DatabaseDialect dialect, DataSource connections) {
        return resolved(access, dialect, connections)
            .map(MigrationInfo::getVersion)
            .max(Comparator.naturalOrder())
            .map(MigrationVersion::getVersion)
            .orElseThrow(ModlSchema::noMigrations);
    }

    static boolean isNewer(String version, String than) {
        return MigrationVersion.fromVersion(version).compareTo(MigrationVersion.fromVersion(than)) > 0;
    }

    static void requireSupported(String version, String latest) {
        if (isNewer(version, latest)) {
            throw new DatabaseToolException("This data has database schema version " + version + ", which is newer than this modl"
                + " supports (" + latest + "). Use the modl-server jar of the newer version.");
        }
    }

    private static Stream<MigrationInfo> resolved(DatabaseAccess access, DatabaseDialect dialect, DataSource connections) {
        return Arrays.stream(access.flyway(dialect, connections).table(UNCREATED_HISTORY).load().info().all())
            .filter(migration -> migration.getVersion() != null && migration.getState().isResolved());
    }

    private static DatabaseToolException noMigrations() {
        return new DatabaseToolException("No database migration was found; the server jar is incomplete");
    }
}
