package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseDialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;

final class CreatedTableRecorder implements Callback {
    private final DatabaseAccess access;
    private final DatabaseDialect dialect;
    private final DataSource connections;
    private final Set<String> modlTables;
    private final Set<String> presentBefore;

    CreatedTableRecorder(DatabaseAccess access, DatabaseDialect dialect, DataSource connections, Set<String> modlTables,
                         Set<String> presentBefore) {
        this.access = access;
        this.dialect = dialect;
        this.connections = connections;
        this.modlTables = Set.copyOf(modlTables);
        this.presentBefore = Set.copyOf(presentBefore);
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.AFTER_EACH_MIGRATE
            || (event == Event.AFTER_EACH_MIGRATE_ERROR && dialect.keepsDdlOfAFailedMigration());
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        try {
            if (event == Event.AFTER_EACH_MIGRATE_ERROR) {
                recordOnOwnConnection();
            } else {
                record(context.getConnection());
            }
        } catch (SQLException e) {
            throw new FlywayException("Could not record the tables modl created in the target database", e);
        }
    }

    private void recordOnOwnConnection() throws SQLException {
        try (Connection connection = connections.getConnection()) {
            record(connection);
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
        }
    }

    private void record(Connection connection) throws SQLException {
        Set<String> created = new HashSet<>(SchemaCatalog.allTableNames(connection, dialect));
        created.removeAll(presentBefore);
        created.retainAll(modlTables);
        RestoreTableList.add(access.queries(connection, dialect), created);
    }

    @Override
    public String getCallbackName() {
        return "modl-created-table-recorder";
    }
}
