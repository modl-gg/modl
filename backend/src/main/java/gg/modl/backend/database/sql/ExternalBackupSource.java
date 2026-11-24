package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseDialect;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.jooq.DSLContext;

final class ExternalBackupSource implements BackupSource {
    private static final String MYSQL_CURSOR_FETCH = "useCursorFetch";
    private static final String MYSQL_REPEATABLE_READ = "SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ";
    private static final String MYSQL_CONSISTENT_SNAPSHOT = "START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY";
    private static final String POSTGRESQL_CONSISTENT_SNAPSHOT = "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY";
    private static final String MYSQL_SIZES = "SELECT table_name, COALESCE(data_length, 0) FROM information_schema.tables "
        + "WHERE table_schema = DATABASE()";
    private static final String POSTGRESQL_SIZES = "SELECT c.relname, pg_table_size(c.oid) FROM pg_class c "
        + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = current_schema() AND c.relkind = 'r'";

    private final DatabaseAccess access;
    private final DatabaseDialect dialect;
    private final SqlStatements statements;
    private final DataSource connections;

    ExternalBackupSource(DatabaseAccess access) {
        this.access = access;
        this.dialect = access.database().dialect();
        this.statements = access.statements();
        DriverDataSource sessions = ExternalSessions.migration(DriverDataSource.of(access.database()), dialect);
        this.connections = access.tracked(dialect == DatabaseDialect.MYSQL
            ? sessions.withProperty(MYSQL_CURSOR_FETCH, "true")
            : sessions);
    }

    @Override
    public DatabaseDialect dialect() {
        return dialect;
    }

    @Override
    public boolean exists() {
        try (Connection connection = connections.getConnection()) {
            return SchemaState.read(access, connection, dialect, connections).version().isPresent();
        } catch (SQLException e) {
            throw DatabaseToolException.failedTo("read the database", e);
        }
    }

    @Override
    public long sizeBytes() {
        try (Connection connection = connections.getConnection()) {
            Set<String> owned = SchemaState.read(access, connection, dialect, connections).ownedTables(access.schemas(), Set.of());
            return statements.rows(connection, dialect.isMysqlFamily() ? MYSQL_SIZES : POSTGRESQL_SIZES).stream()
                .filter(table -> owned.contains(table.get(0)))
                .mapToLong(table -> Long.parseLong(table.get(1)))
                .sum();
        } catch (SQLException e) {
            throw DatabaseToolException.failedTo("measure the database", e);
        }
    }

    @Override
    public void requireServing() {
        access.fence().requireServing();
    }

    @Override
    public <T> T readConsistently(ViewReader<T> reader) throws IOException {
        try (Connection connection = connections.getConnection()) {
            beginConsistentSnapshot(connection);
            DSLContext dsl = access.queries(connection, dialect);
            return reader.read(DatabaseView.of(access, connection, dialect, connections, dsl));
        } catch (SQLException e) {
            access.fence().requireServing();
            throw DatabaseToolException.failedTo("read a consistent snapshot of the database", e);
        }
    }

    @Override
    public Optional<SqliteSnapshots> snapshots() {
        return Optional.empty();
    }

    private void beginConsistentSnapshot(Connection connection) throws SQLException {
        if (dialect.isMysqlFamily()) {
            statements.execute(connection, MYSQL_REPEATABLE_READ);
            statements.execute(connection, MYSQL_CONSISTENT_SNAPSHOT);
        } else {
            connection.setAutoCommit(false);
            statements.execute(connection, POSTGRESQL_CONSISTENT_SNAPSHOT);
        }
    }

    @Override
    public Set<String> tablesAnUpgradeCreates() {
        Set<String> created = new HashSet<>(access.schemas().latest().tables());
        try (Connection connection = connections.getConnection()) {
            created.removeAll(SchemaCatalog.allTableNames(connection, dialect));
            if (!connection.getAutoCommit()) {
                connection.rollback();
            }
        } catch (SQLException e) {
            throw DatabaseToolException.failedTo("read the tables of the database", e);
        }
        return created;
    }
}
