package gg.modl.backend.database.selection;

import gg.modl.address.IpLiteral;
import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.sql.SqlDatabaseProperties;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public record DatabaseConnection(
    DatabaseDialect dialect,
    String host,
    int port,
    String database,
    boolean tls,
    @Nullable String username,
    @Nullable String password
) {
    public static final int MAX_HOST_LENGTH = 253;
    public static final int MAX_USERNAME_LENGTH = 128;
    public static final int MAX_PASSWORD_LENGTH = 512;
    public static final int MAX_PORT = 65535;

    private static final Pattern HOST_NAME = Pattern.compile(
        "(?i)[a-z0-9](?:[a-z0-9_-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9_-]{0,61}[a-z0-9])?)*");
    private static final Pattern DATABASE_NAME = Pattern.compile("[A-Za-z0-9_$-]{1,64}");

    public DatabaseConnection {
        if (dialect == DatabaseDialect.SQLITE) {
            host = "";
            port = 0;
            database = "";
            tls = false;
            username = null;
            password = null;
        } else {
            host = requireHost(host.trim());
            requirePort(port);
            database = requireDatabase(database.trim());
            username = requireUsername(username);
            password = password == null || password.isEmpty() ? null : requirePassword(password);
        }
    }

    public static DatabaseConnection builtIn() {
        return new DatabaseConnection(DatabaseDialect.SQLITE, "", 0, "", false, null, null);
    }

    public boolean isBuiltIn() {
        return dialect == DatabaseDialect.SQLITE;
    }

    public DatabaseLocation location() {
        return isBuiltIn() ? DatabaseLocation.BUILT_IN : new DatabaseLocation(dialect, host, port, database);
    }

    public SqlDatabaseProperties properties(SqlDatabaseProperties base) {
        return new SqlDatabaseProperties(isBuiltIn() ? null : jdbcUrl(), username, password, base.poolSize(), base.sqlite());
    }

    public String describe() {
        return location().describe();
    }

    @Override
    public String toString() {
        return "DatabaseConnection[" + describe() + ", tls=" + tls + ", credentials=" + (password == null ? "user only" : "redacted")
            + "]";
    }

    private String jdbcUrl() {
        String address = IpLiteral.isIpv6Literal(host) ? "[" + host + "]" : host;
        return dialect.urlPrefix() + address + ":" + port + "/" + database + (tls ? tlsParameter() : "");
    }

    private String tlsParameter() {
        return switch (dialect) {
            case MYSQL -> "?sslMode=VERIFY_IDENTITY";
            case MARIADB -> "?sslMode=verify-full";
            case POSTGRESQL -> "?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory";
            case SQLITE -> "";
        };
    }

    private static String requireHost(String host) {
        boolean valid = host.length() <= MAX_HOST_LENGTH
            && (HOST_NAME.matcher(host).matches() || IpLiteral.isIpv6Literal(host));
        if (!valid) {
            throw new IllegalArgumentException("Enter a host name or IP address for the database server");
        }
        return host;
    }

    private static void requirePort(int port) {
        if (port < 1 || port > MAX_PORT) {
            throw new IllegalArgumentException("Enter a port between 1 and " + MAX_PORT);
        }
    }

    private static String requireDatabase(String database) {
        if (!DATABASE_NAME.matcher(database).matches()) {
            throw new IllegalArgumentException("Enter a database name of at most 64 letters, digits, _, $, or -");
        }
        return database;
    }

    private static String requireUsername(@Nullable String username) {
        String trimmed = username == null ? "" : username.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_USERNAME_LENGTH || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Enter the database user name (at most " + MAX_USERNAME_LENGTH + " characters)");
        }
        return trimmed;
    }

    private static String requirePassword(String password) {
        if (password.length() > MAX_PASSWORD_LENGTH || password.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("The database password is longer than " + MAX_PASSWORD_LENGTH
                + " characters or holds control characters");
        }
        return password;
    }
}
