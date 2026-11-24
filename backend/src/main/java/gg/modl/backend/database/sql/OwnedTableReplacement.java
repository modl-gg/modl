package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseDialect;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;

final class OwnedTableReplacement {
    private static final Table<Record> UNFINISHED = DSL.table(DSL.name(ModlSchema.UNFINISHED_TABLE));

    private final DatabaseAccess access;
    private final DatabaseDialect dialect;
    private final DataSource connections;
    private final DataSource loads;

    OwnedTableReplacement(DatabaseAccess access, DatabaseDialect dialect, DataSource connections, DataSource loads) {
        this.access = access;
        this.dialect = dialect;
        this.connections = connections;
        this.loads = loads;
    }

    void replace(DatabaseView replacement, Set<String> newerVersionTables) throws IOException {
        access.schemas().requireSupported(replacement.schemaVersion());
        clearOwnedTables(replacement.catalog(), newerVersionTables);
        createTables(replacement);
        copyRows(replacement);
        finish();
    }

    private void clearOwnedTables(SchemaCatalog replacement, Set<String> newerVersionTables) {
        access.fence().requireServing();
        try (Connection connection = connections.getConnection()) {
            SchemaState current = SchemaState.read(access, connection, dialect, connections);
            new RestoreBlockers(access, dialect).requireNone(connection, current, replacement, newerVersionTables);
            Set<String> owned = current.ownedTables(access.schemas(), newerVersionTables);
            List<String> childrenFirst = SchemaCatalog.childrenFirst(connection, dialect, owned);
            boolean renamesHistory = current.version().isPresent();
            if (!renamesHistory && !current.unfinished()) {
                createEmptyHistoryBesideOtherTables();
            }
            if (dialect == DatabaseDialect.POSTGRESQL) {
                connection.setAutoCommit(false);
            }
            DSLContext dsl = access.queries(connection, dialect);
            recordTables(dsl, current, owned);
            if (renamesHistory) {
                if (current.unfinished()) {
                    dsl.dropTable(UNFINISHED).execute();
                }
                rename(dsl, ModlSchema.HISTORY_TABLE, ModlSchema.UNFINISHED_TABLE);
            }
            dropOrPutBack(connection, dsl, childrenFirst, renamesHistory);
            dsl.deleteFrom(UNFINISHED).execute();
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
        } catch (SQLException e) {
            throw DatabaseToolException.failedTo("clear modl's tables in the target database", e);
        }
    }

    private void createEmptyHistoryBesideOtherTables() {
        access.flyway(dialect, connections)
            .table(ModlSchema.UNFINISHED_TABLE)
            .load()
            .baseline();
    }

    private static void recordTables(DSLContext dsl, SchemaState current, Set<String> owned) {
        boolean continuesUnfinishedRestore = current.version().isEmpty() && current.unfinished();
        if (current.listsRestoreTables() && !continuesUnfinishedRestore) {
            RestoreTableList.drop(dsl);
        }
        if (!current.listsRestoreTables() || !continuesUnfinishedRestore) {
            RestoreTableList.create(dsl);
        }
        RestoreTableList.add(dsl, owned);
    }

    private void createTables(DatabaseView replacement) {
        FluentConfiguration migration = access.flyway(dialect, connections)
            .table(ModlSchema.UNFINISHED_TABLE)
            .target(replacement.schemaVersion());
        Callback recorder = new CreatedTableRecorder(
            access, dialect, connections, replacement.catalog().tableNames(), presentTableNames());
        migration.callbacks(Stream.concat(Arrays.stream(migration.getCallbacks()), Stream.of(recorder)).toArray(Callback[]::new))
            .load()
            .migrate();
    }

    private Set<String> presentTableNames() {
        try (Connection connection = connections.getConnection()) {
            return SchemaCatalog.allTableNames(connection, dialect);
        } catch (SQLException e) {
            throw DatabaseToolException.failedTo("read the tables of the target database", e);
        }
    }

    private void dropOrPutBack(Connection connection, DSLContext dsl, List<String> childrenFirst, boolean renamedHistory)
        throws SQLException {
        if (childrenFirst.isEmpty()) {
            return;
        }
        access.fence().requireServing();
        try {
            if (dialect == DatabaseDialect.SQLITE) {
                childrenFirst.forEach(table -> dsl.dropTable(DSL.name(table)).execute());
            } else {
                dsl.execute("DROP TABLE " + childrenFirst.stream()
                    .map(table -> dsl.render(DSL.name(table)))
                    .collect(Collectors.joining(", ")));
            }
            RestoreTableList.remove(dsl, childrenFirst);
        } catch (DataAccessException e) {
            boolean unchanged = putBack(connection, dsl, childrenFirst, renamedHistory);
            if (!unchanged) {
                RestoreTableList.retainOnly(dsl, SchemaCatalog.allTableNames(connection, dialect));
            }
            throw new DatabaseToolException("The database refused to drop modl's tables " + String.join(", ", childrenFirst)
                + (unchanged ? ", so nothing was changed" : "; run restore again once the cause is fixed")
                + ". Tables that modl does not own were not touched. Cause: " + causeOf(e), e);
        }
    }

    private boolean putBack(Connection connection, DSLContext dsl, List<String> tables, boolean renamedHistory) throws SQLException {
        if (!connection.getAutoCommit()) {
            connection.rollback();
            return true;
        }
        if (!renamedHistory || !SchemaCatalog.allTableNames(connection, dialect).containsAll(tables)) {
            return false;
        }
        rename(dsl, ModlSchema.UNFINISHED_TABLE, ModlSchema.HISTORY_TABLE);
        RestoreTableList.drop(dsl);
        return true;
    }

    private void copyRows(DatabaseView replacement) throws IOException {
        try (Connection connection = loads.getConnection()) {
            new TableLoader(connection, access.queries(connection, dialect), access.fence())
                .load(replacement.catalog().tables(), replacement::forEachRow);
        } catch (SQLException e) {
            throw DatabaseToolException.failedTo("fill the target database", e);
        }
    }

    private void finish() {
        access.fence().requireServing();
        try (Connection connection = connections.getConnection()) {
            DSLContext dsl = access.queries(connection, dialect);
            rename(dsl, ModlSchema.UNFINISHED_TABLE, ModlSchema.HISTORY_TABLE);
            RestoreTableList.drop(dsl);
        } catch (SQLException e) {
            throw DatabaseToolException.failedTo("finish replacing modl's tables", e);
        }
    }

    private static void rename(DSLContext dsl, String from, String to) {
        dsl.alterTable(DSL.name(from)).renameTo(DSL.name(to)).execute();
    }

    private static String causeOf(DataAccessException failure) {
        SQLException cause = failure.getCause(SQLException.class);
        String message = cause == null ? null : cause.getMessage();
        return message == null ? "unknown" : message;
    }
}
