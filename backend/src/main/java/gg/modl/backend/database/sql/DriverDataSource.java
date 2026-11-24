package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseDialect;
import org.jspecify.annotations.Nullable;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;

final class DriverDataSource implements DataSource {
    private static final String USER = "user";
    private static final String PASSWORD = "password";

    private final Driver driver;
    private final String url;
    private final Properties properties;

    private DriverDataSource(Driver driver, String url, Properties properties) {
        this.driver = driver;
        this.url = url;
        this.properties = properties;
    }

    static DriverDataSource of(SqlDatabaseProperties database) {
        DatabaseDialect dialect = database.dialect();
        Properties properties = new Properties();
        putIfPresent(properties, USER, database.username());
        putIfPresent(properties, PASSWORD, database.password());
        return new DriverDataSource(loadDriver(dialect), database.externalUrl().orElseThrow(), properties);
    }

    DriverDataSource withProperty(String name, String value) {
        Properties copy = new Properties();
        copy.putAll(properties);
        copy.setProperty(name, value);
        return new DriverDataSource(driver, url, copy);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = driver.connect(url, properties);
        if (connection == null) {
            throw new SQLException("The " + driver.getClass().getName() + " driver does not accept the configured URL");
        }
        return connection;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLFeatureNotSupportedException("Credentials come from the modl database settings");
    }

    @Override
    public @Nullable PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
    }

    @Override
    public void setLoginTimeout(int seconds) {
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        throw new SQLException("Not a wrapper for " + type.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
        return type.isInstance(this);
    }

    private static Driver loadDriver(DatabaseDialect dialect) {
        try {
            Class<?> driverClass = Class.forName(dialect.driverClassName(), true, DriverDataSource.class.getClassLoader());
            return (Driver) driverClass.getConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("The " + dialect + " JDBC driver " + dialect.driverClassName() + " could not be loaded", e);
        }
    }

    private static void putIfPresent(Properties properties, String name, @Nullable String value) {
        if (value != null) {
            properties.setProperty(name, value);
        }
    }
}
