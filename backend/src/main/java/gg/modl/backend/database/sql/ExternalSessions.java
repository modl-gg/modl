package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseDialect;
import java.time.Duration;

final class ExternalSessions {
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration POOL_SOCKET_TIMEOUT = Duration.ofSeconds(60);
    static final Duration LOCK_SOCKET_TIMEOUT = Duration.ofSeconds(5);
    static final String MYSQL_SESSION_VARIABLES_PROPERTY = "sessionVariables";
    static final String MYSQL_SESSION_VARIABLES = "sql_mode='STRICT_ALL_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE,"
        + "ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION',innodb_lock_wait_timeout=5";
    static final String MYSQL_AFFECTED_ROWS_PROPERTY = "useAffectedRows";
    static final String POSTGRESQL_OPTIONS_PROPERTY = "options";
    static final String POSTGRESQL_OPTIONS = "-c synchronous_commit=on -c lock_timeout=5s";

    private static final String CONNECT_TIMEOUT_PROPERTY = "connectTimeout";
    private static final String SOCKET_TIMEOUT_PROPERTY = "socketTimeout";

    private ExternalSessions() {
    }

    static DriverDataSource pooled(DriverDataSource driver, DatabaseDialect dialect) {
        return configured(driver, dialect).withProperty(SOCKET_TIMEOUT_PROPERTY, timeout(dialect, POOL_SOCKET_TIMEOUT));
    }

    static DriverDataSource migration(DriverDataSource driver, DatabaseDialect dialect) {
        return configured(driver, dialect);
    }

    static DriverDataSource lock(DriverDataSource driver, DatabaseDialect dialect) {
        return configured(driver, dialect).withProperty(SOCKET_TIMEOUT_PROPERTY, timeout(dialect, LOCK_SOCKET_TIMEOUT));
    }

    private static DriverDataSource configured(DriverDataSource driver, DatabaseDialect dialect) {
        DriverDataSource timed = driver.withProperty(CONNECT_TIMEOUT_PROPERTY, timeout(dialect, CONNECT_TIMEOUT));
        return dialect.isMysqlFamily()
            ? timed.withProperty(MYSQL_SESSION_VARIABLES_PROPERTY, MYSQL_SESSION_VARIABLES)
                .withProperty(MYSQL_AFFECTED_ROWS_PROPERTY, "false")
            : timed.withProperty(POSTGRESQL_OPTIONS_PROPERTY, POSTGRESQL_OPTIONS);
    }

    private static String timeout(DatabaseDialect dialect, Duration timeout) {
        return String.valueOf(dialect.isMysqlFamily() ? timeout.toMillis() : timeout.toSeconds());
    }
}
