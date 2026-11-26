package gg.modl.testing.litebans;

import litebans.api.Database;
import org.bukkit.Bukkit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

final class FixtureDatabase extends Database {
    static final String UNREADABLE_PLAYER = "00000000-0000-4000-8000-00000000dead";

    private static final String[] TABLES = {"bans", "mutes", "warnings", "kicks", "history", "servers"};
    private static final String TABLE_PREFIX = "litebans_";

    private final Connection connection;

    FixtureDatabase(Connection connection) {
        this.connection = connection;
    }

    @Override
    public PreparedStatement prepareStatement(String query) throws SQLException {
        if (Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("LiteBans queries must not run on the main thread");
        }
        String sql = query;
        for (String table : TABLES) sql = sql.replace("{" + table + "}", TABLE_PREFIX + table);
        return failingForUnreadablePlayer(connection.prepareStatement(sql));
    }

    private static PreparedStatement failingForUnreadablePlayer(PreparedStatement statement) {
        AtomicBoolean unreadable = new AtomicBoolean();
        return (PreparedStatement) Proxy.newProxyInstance(FixtureDatabase.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                    if ("setString".equals(method.getName())) unreadable.set(UNREADABLE_PLAYER.equals(arguments[1]));
                    if ("executeQuery".equals(method.getName()) && unreadable.get()) {
                        throw new SQLException("The fixture player cannot be read");
                    }
                    try {
                        return method.invoke(statement, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }
}
