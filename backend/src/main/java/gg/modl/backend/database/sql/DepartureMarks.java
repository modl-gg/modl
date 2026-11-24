package gg.modl.backend.database.sql;

import static gg.modl.backend.database.jooq.Tables.DATABASE_DEPARTURE;

import gg.modl.backend.database.DatabaseDialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public final class DepartureMarks {
    private static final String TABLE = DATABASE_DEPARTURE.getName();
    public static final int ROW_ID = 1;

    private DepartureMarks() {
    }

    public static Optional<DatabaseDeparture> read(DataSource connections, DatabaseDialect dialect) {
        try (Connection connection = connections.getConnection()) {
            return read(connection, dialect);
        } catch (SQLException e) {
            throw new IllegalStateException("The database could not be checked for an earlier move", e);
        }
    }

    public static void clear(DataSource connections, DatabaseDialect dialect) {
        try (Connection connection = connections.getConnection()) {
            delete(connection, dialect);
        } catch (SQLException e) {
            throw new IllegalStateException("The record of an earlier database move could not be removed", e);
        }
    }

    public static boolean isMarkTable(String tableName) {
        return TABLE.equals(tableName);
    }

    static void clearIfPresent(Connection connection, DatabaseDialect dialect) throws SQLException {
        if (SchemaCatalog.allTableNames(connection, dialect).contains(TABLE)) {
            delete(connection, dialect);
        }
    }

    private static void delete(Connection connection, DatabaseDialect dialect) throws SQLException {
        queries(connection, dialect).deleteFrom(DATABASE_DEPARTURE).where(DATABASE_DEPARTURE.ID.eq(ROW_ID)).execute();
        if (!connection.getAutoCommit()) {
            connection.commit();
        }
    }

    static Optional<DatabaseDeparture> read(Connection connection, DatabaseDialect dialect) throws SQLException {
        if (!SchemaCatalog.allTableNames(connection, dialect).contains(TABLE)) {
            return Optional.empty();
        }
        return queries(connection, dialect)
            .select(DATABASE_DEPARTURE.MOVE_TOKEN, DATABASE_DEPARTURE.MOVED_TO, DATABASE_DEPARTURE.MOVED_AT)
            .from(DATABASE_DEPARTURE)
            .where(DATABASE_DEPARTURE.ID.eq(ROW_ID))
            .fetchOptional(row -> new DatabaseDeparture(row.value1(), row.value2(), row.value3()));
    }

    private static DSLContext queries(Connection connection, DatabaseDialect dialect) {
        return DSL.using(connection, dialect.sqlDialect(), JooqConfiguration.settings());
    }
}
