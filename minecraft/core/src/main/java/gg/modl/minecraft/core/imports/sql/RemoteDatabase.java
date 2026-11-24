package gg.modl.minecraft.core.imports.sql;

import lombok.Builder;
import lombok.Value;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

@Value
@Builder
public class RemoteDatabase {
    private static final String CONNECT_TIMEOUT_MILLIS = "10000";
    private static final String CONNECT_TIMEOUT_SECONDS = "10";
    private static final Set<String> MARIADB_PROPERTIES = new HashSet<>(Arrays.asList("sslmode", "serversslcert",
            "allowpublickeyretrieval"));
    private static final Set<String> POSTGRESQL_PROPERTIES = new HashSet<>(Arrays.asList("ssl", "sslmode", "sslrootcert"));
    private static final Pattern DATABASE_NAME = Pattern.compile("[A-Za-z0-9_$.-]{1,64}");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9_.:\\[\\]-]{1,255}");

    JdbcDriver driver;
    String host;
    int port;
    String database;
    String username;
    DatabasePassword password;
    @Builder.Default Map<String, String> properties = Collections.emptyMap();

    SqlSession open(JdbcConnections connections) throws SQLException {
        Properties settings = new Properties();
        boolean postgres = driver == JdbcDriver.POSTGRESQL;
        Map<String, String> configured = postgres ? properties : TransportSecurity.forMariaDb(properties);
        Set<String> allowed = postgres ? POSTGRESQL_PROPERTIES : MARIADB_PROPERTIES;
        configured.forEach((name, value) -> {
            if (allowed.contains(name.toLowerCase(Locale.ROOT))) settings.setProperty(name, value);
        });
        settings.setProperty("user", username);
        settings.setProperty("password", password.reveal());
        if (postgres) {
            settings.setProperty("connectTimeout", CONNECT_TIMEOUT_SECONDS);
            settings.setProperty("loginTimeout", CONNECT_TIMEOUT_SECONDS);
            settings.setProperty("readOnly", "true");
        } else {
            settings.setProperty("connectTimeout", CONNECT_TIMEOUT_MILLIS);
            settings.setProperty("allowLocalInfile", "false");
        }
        return connections.session(driver, url(), settings, connection -> {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
        }, () -> { });
    }

    private String url() throws SQLException {
        if (!DATABASE_NAME.matcher(database).matches() || !HOST.matcher(host).matches()) {
            throw new SQLException("The configured database host or name is not supported: " + this);
        }
        String scheme = driver == JdbcDriver.POSTGRESQL ? "jdbc:postgresql://" : "jdbc:mariadb://";
        return scheme + host + ":" + port + "/" + database;
    }

    @Override
    public String toString() {
        return driver + " database " + database + " at " + host + ":" + port;
    }
}
