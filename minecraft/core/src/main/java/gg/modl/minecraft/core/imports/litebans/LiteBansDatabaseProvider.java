package gg.modl.minecraft.core.imports.litebans;

import gg.modl.minecraft.api.DatabaseProvider;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class LiteBansDatabaseProvider implements DatabaseProvider {
    private static final String LITEBANS_DATABASE_CLASS = "litebans.api.Database";

    private final Object databaseInstance;
    private final Method prepareStatementMethod;

    public LiteBansDatabaseProvider() throws Exception {
        Class<?> databaseClass = Class.forName(LITEBANS_DATABASE_CLASS);
        this.databaseInstance = databaseClass.getMethod("get").invoke(null);
        this.prepareStatementMethod = databaseClass.getMethod("prepareStatement", String.class);
    }

    @Override
    public PreparedStatement prepareStatement(String query) throws SQLException {
        try {
            return (PreparedStatement) prepareStatementMethod.invoke(databaseInstance, query);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SQLException) throw (SQLException) cause;
            throw new SQLException("LiteBans could not prepare the statement: " + cause, cause);
        } catch (ReflectiveOperationException e) {
            throw new SQLException("Failed to prepare statement using LiteBans API", e);
        }
    }

    @Override
    public void close() {
    }
}
