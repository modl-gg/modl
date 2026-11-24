package gg.modl.backend.database.sql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;

public final class SqliteForeignKeyCheckCallback implements Callback {
    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.AFTER_EACH_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        try (Statement statement = context.getConnection().createStatement();
             ResultSet violations = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (violations.next()) {
                throw new FlywayException("Migration " + describe(context) + " left a row in " + violations.getString(1)
                    + " that refers to a missing row in " + violations.getString(3));
            }
        } catch (SQLException e) {
            throw new FlywayException("The foreign key check after migration " + describe(context) + " failed", e);
        }
    }

    @Override
    public String getCallbackName() {
        return "sqlite-foreign-key-check";
    }

    private static String describe(Context context) {
        return context.getMigrationInfo() == null ? "?" : String.valueOf(context.getMigrationInfo().getVersion());
    }
}
