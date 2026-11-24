package gg.modl.backend.database.sql;

import static gg.modl.backend.database.jooq.Tables.AUTH_CODES;
import static gg.modl.backend.database.jooq.Tables.EVIDENCE_UPLOAD_TOKENS;
import static gg.modl.backend.database.jooq.Tables.MINECRAFT_LINK_CODES;
import static gg.modl.backend.database.jooq.Tables.PANEL_LOGIN_TOKENS;
import static gg.modl.backend.database.jooq.Tables.PANEL_SESSIONS;
import static gg.modl.backend.database.jooq.Tables.STAFF;
import static gg.modl.backend.database.jooq.Tables.WEBAUTHN_CHALLENGES;

import gg.modl.backend.database.DatabaseDialect;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.Table;

public final class SqliteFile {
    private static final String INTEGRITY_OK = "ok";
    private static final String SCHEMA_OBJECTS_THAT_CAN_RUN_CODE = "SELECT count(*) FROM sqlite_master "
        + "WHERE type NOT IN ('table', 'index') OR sql LIKE 'CREATE VIRTUAL%'";

    private static final String OBJECTS_AND_THEIR_TABLES = "SELECT name, tbl_name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'";

    private final DatabaseAccess access;
    private final SqliteConnections connections;
    private final SqlStatements statements;
    private final DataSource snapshots;
    private final DataSource migrations;

    SqliteFile(DatabaseAccess access, SqliteConnections connections) {
        this.access = access;
        this.connections = connections;
        this.statements = access.statements();
        this.snapshots = access.tracked(connections.snapshot());
        this.migrations = access.tracked(connections.migration());
    }

    public Path path() {
        return connections.databaseFile();
    }

    Path directory() {
        return connections.databaseDirectory();
    }

    public void create(String schemaVersion) {
        SqliteDatabaseFile.bootstrap(connections, access.tracker(), access.notice());
        migrateTo(schemaVersion);
    }

    public void migrateTo(String schemaVersion) {
        access.schemas().requireSupported(schemaVersion);
        access.flyway(DatabaseDialect.SQLITE, migrations)
            .target(schemaVersion)
            .load()
            .migrate();
    }

    public void forgetDatabaseMove() {
        try (Connection connection = migrations.getConnection()) {
            DepartureMarks.clearIfPresent(connection, DatabaseDialect.SQLITE);
        } catch (SQLException e) {
            throw failedTo("remove the database move record from the copy", e);
        }
    }

    public void requireIntact() {
        try (Connection connection = snapshots.getConnection()) {
            String result = statements.text(connection, "PRAGMA integrity_check");
            if (!INTEGRITY_OK.equalsIgnoreCase(result)) {
                throw new DatabaseToolException("The database copy " + path().getFileName() + " is damaged: " + result);
            }
        } catch (SQLException e) {
            throw failedTo("check the database copy", e);
        }
    }

    public void requirePlainSchema() {
        try (Connection connection = snapshots.getConnection()) {
            if (statements.number(connection, SCHEMA_OBJECTS_THAT_CAN_RUN_CODE) > 0) {
                throw new DatabaseToolException("The database file holds triggers, views or virtual tables, which a modl database never has");
            }
        } catch (SQLException e) {
            throw failedTo("read the schema of the database file", e);
        }
    }

    public void requireValidReferences() {
        try (Connection connection = snapshots.getConnection()) {
            long dangling = statements.number(connection, "SELECT count(*) FROM pragma_foreign_key_check");
            if (dangling > 0) {
                throw new DatabaseToolException("The database copy holds " + dangling + " rows that refer to missing rows");
            }
        } catch (SQLException e) {
            throw failedTo("check the references in the database copy", e);
        }
    }

    public boolean usesIncrementalAutoVacuum() {
        try (Connection connection = snapshots.getConnection()) {
            return statements.number(connection, "PRAGMA auto_vacuum") == SqliteDatabaseFile.INCREMENTAL_AUTO_VACUUM;
        } catch (SQLException e) {
            throw failedTo("read the auto-vacuum mode of the database copy", e);
        }
    }

    public SqliteFile convertedToIncrementalAutoVacuum(Path target) throws IOException {
        try (Connection connection = snapshots.getConnection()) {
            statements.execute(connection, "PRAGMA auto_vacuum = INCREMENTAL");
            try (PreparedStatement vacuum = connection.prepareStatement("VACUUM INTO ?")) {
                vacuum.setString(1, target.toString());
                access.running().register(vacuum);
                try {
                    vacuum.execute();
                } finally {
                    access.running().deregister(vacuum);
                }
            }
        } catch (SQLException e) {
            throw failedTo("convert the database copy to incremental auto-vacuum", e);
        }
        SqliteFile converted = access.sqliteFile(target);
        if (!converted.usesIncrementalAutoVacuum()) {
            throw new DatabaseToolException("The database copy could not be converted to incremental auto-vacuum");
        }
        Files.delete(path());
        return converted;
    }

    public void load(List<SchemaTable> parentsFirst, RowFeed feed) throws IOException {
        try (Connection connection = migrations.getConnection()) {
            DSLContext dsl = access.queries(connection, DatabaseDialect.SQLITE);
            new TableLoader(connection, dsl, access.fence()).load(parentsFirst, feed);
        } catch (SQLException e) {
            throw failedTo("fill the database copy", e);
        }
    }

    public void revokeShortLivedAccess() throws IOException {
        try (Connection connection = migrations.getConnection()) {
            DSLContext dsl = access.queries(connection, DatabaseDialect.SQLITE);
            for (Table<?> table : List.of(PANEL_SESSIONS, AUTH_CODES, WEBAUTHN_CHALLENGES, PANEL_LOGIN_TOKENS, MINECRAFT_LINK_CODES,
                EVIDENCE_UPLOAD_TOKENS)) {
                dsl.deleteFrom(table).execute();
            }
            dsl.update(STAFF)
                .setNull(STAFF.TWO_FACTOR_TOKEN_HASH)
                .setNull(STAFF.TWO_FACTOR_TOKEN_IP)
                .setNull(STAFF.TWO_FACTOR_TOKEN_CREATED_AT)
                .set(STAFF.TWO_FACTOR_PENDING_DELIVERY, false)
                .setNull(STAFF.TWO_FACTOR_SESSION_IP)
                .setNull(STAFF.TWO_FACTOR_SESSION_EXPIRES_AT)
                .execute();
        } catch (SQLException e) {
            throw failedTo("clear sign-in sessions and codes in the database copy", e);
        }
    }

    public <T> T read(ViewReader<T> reader) throws IOException {
        try (Connection connection = snapshots.getConnection()) {
            connection.setAutoCommit(false);
            DSLContext dsl = access.queries(connection, DatabaseDialect.SQLITE);
            return reader.read(DatabaseView.of(access, connection, DatabaseDialect.SQLITE, snapshots, dsl));
        } catch (SQLException e) {
            throw failedTo("read " + path(), e);
        }
    }

    void replaceOwnedTables(DatabaseView replacement, Set<String> newerVersionTables) throws IOException {
        new OwnedTableReplacement(access, DatabaseDialect.SQLITE, migrations, migrations).replace(replacement, newerVersionTables);
    }

    SchemaState state() {
        try (Connection connection = snapshots.getConnection()) {
            return SchemaState.read(access, connection, DatabaseDialect.SQLITE, snapshots);
        } catch (SQLException e) {
            throw failedTo("read " + path(), e);
        }
    }

    void requireNoBlockers(SchemaCatalog replacement, Set<String> newerVersionTables) {
        try (Connection connection = snapshots.getConnection()) {
            SchemaState current = SchemaState.read(access, connection, DatabaseDialect.SQLITE, snapshots);
            new RestoreBlockers(access, DatabaseDialect.SQLITE).requireNone(connection, current, replacement, newerVersionTables);
        } catch (SQLException e) {
            throw failedTo("read " + path(), e);
        }
    }

    boolean holdsObjectsOutside(Set<String> tables) {
        try (Connection connection = snapshots.getConnection()) {
            return statements.rows(connection, OBJECTS_AND_THEIR_TABLES).stream().anyMatch(object -> !tables.contains(object.get(1)));
        } catch (SQLException e) {
            throw failedTo("read " + path(), e);
        }
    }

    long sizeBytes() {
        try (Connection connection = snapshots.getConnection()) {
            return statements.number(connection, "PRAGMA page_count") * statements.number(connection, "PRAGMA page_size");
        } catch (SQLException e) {
            throw failedTo("measure " + path(), e);
        }
    }

    private DatabaseToolException failedTo(String action, SQLException failure) {
        access.fence().requireServing();
        return DatabaseToolException.failedTo(action, failure);
    }
}
