package litebans.api;

import java.sql.PreparedStatement;
import java.sql.SQLException;

public abstract class Database {
    private static volatile Database instance;

    public static Database get() {
        Database current = instance;
        if (current == null) throw new IllegalStateException("The LiteBans fixture is not running");
        return current;
    }

    public static void setInstance(Database database) {
        instance = database;
    }

    public abstract PreparedStatement prepareStatement(String query) throws SQLException;
}
