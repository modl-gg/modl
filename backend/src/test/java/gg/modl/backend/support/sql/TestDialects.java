package gg.modl.backend.support.sql;

import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.sql.SqlDatabaseProperties;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

public final class TestDialects {
    public static final DialectTarget SQLITE = new DialectTarget("sqlite", DatabaseDialect.SQLITE, null);
    public static final DialectTarget MYSQL = new DialectTarget("mysql-8.4", DatabaseDialect.MYSQL, "mysql:8.4");
    public static final DialectTarget MARIADB = new DialectTarget("mariadb-10.11", DatabaseDialect.MARIADB, "mariadb:10.11");
    public static final DialectTarget POSTGRESQL = new DialectTarget("postgresql-15", DatabaseDialect.POSTGRESQL, "postgres:15");
    public static final DialectTarget MARIADB_LATEST = new DialectTarget("mariadb-11.4", DatabaseDialect.MARIADB, "mariadb:11.4");
    public static final DialectTarget POSTGRESQL_LATEST = new DialectTarget("postgresql-17", DatabaseDialect.POSTGRESQL, "postgres:17");

    private static final String REQUIRE_ALL = "MODL_REQUIRE_ALL_DIALECTS";
    private static final String ADMIN_USER = "root";
    private static final String PASSWORD = "modl-test";
    private static final String MYSQL_DATA_DIRECTORY = "/var/lib/mysql";
    private static final String POSTGRESQL_DATA_DIRECTORY = "/var/lib/postgresql/data";
    private static final String IN_MEMORY_MOUNT = "rw";
    private static final Map<String, JdbcDatabaseContainer<?>> CONTAINERS = new ConcurrentHashMap<>();
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static volatile Boolean dockerAvailable;

    private TestDialects() {
    }

    public static List<DialectTarget> available() {
        List<DialectTarget> targets = new ArrayList<>(List.of(SQLITE));
        if (externalDialectsAvailable()) {
            targets.addAll(List.of(MYSQL, MARIADB, POSTGRESQL));
            if (allDialectsRequired()) {
                targets.addAll(List.of(MARIADB_LATEST, POSTGRESQL_LATEST));
            }
        }
        return targets;
    }

    public static DialectTarget named(String name) {
        return Stream.of(SQLITE, MYSQL, MARIADB, POSTGRESQL, MARIADB_LATEST, POSTGRESQL_LATEST)
            .filter(target -> target.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown test dialect " + name));
    }

    public static Stream<DialectTarget> availableStream() {
        return available().stream();
    }

    public static List<DialectTarget> external() {
        return available().stream().filter(target -> !target.isSqlite()).toList();
    }

    public static boolean externalDialectsAvailable() {
        if (dockerAvailable == null) {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        }
        if (!dockerAvailable && allDialectsRequired()) {
            throw new IllegalStateException(REQUIRE_ALL + " is set, but Docker is not available");
        }
        return dockerAvailable;
    }

    public static SqlDatabaseProperties freshDatabase(DialectTarget target) {
        if (target.isSqlite()) {
            return new SqlDatabaseProperties(null, null, null, null, null);
        }
        JdbcDatabaseContainer<?> container = container(target);
        String name = "modl_test_" + DATABASES.incrementAndGet();
        execute(container, adminUrl(target, container), "CREATE DATABASE " + name);
        return new SqlDatabaseProperties(databaseUrl(target, container, name), ADMIN_USER, PASSWORD, 4, null);
    }

    static void drop(DialectTarget target, SqlDatabaseProperties database) {
        database.externalUrl().ifPresent(url -> execute(target, "DROP DATABASE " + url.substring(url.lastIndexOf('/') + 1)
            + (target.dialect() == DatabaseDialect.POSTGRESQL ? " WITH (FORCE)" : "")));
    }

    public static void execute(DialectTarget target, String sql) {
        JdbcDatabaseContainer<?> container = container(target);
        execute(container, adminUrl(target, container), sql);
    }

    public static void executeIn(DialectTarget target, SqlDatabaseProperties database, String sql) {
        execute(container(target), database.externalUrl().orElseThrow(), sql);
    }

    private static Connection connect(JdbcDatabaseContainer<?> container, String url) throws SQLException {
        Properties credentials = new Properties();
        credentials.setProperty("user", ADMIN_USER);
        credentials.setProperty("password", PASSWORD);
        return container.getJdbcDriverInstance().connect(url, credentials);
    }

    private static boolean allDialectsRequired() {
        return Boolean.parseBoolean(System.getenv(REQUIRE_ALL));
    }

    private static JdbcDatabaseContainer<?> container(DialectTarget target) {
        return CONTAINERS.computeIfAbsent(target.name(), name -> {
            JdbcDatabaseContainer<?> container = newContainer(target);
            container.start();
            return container;
        });
    }

    private static JdbcDatabaseContainer<?> newContainer(DialectTarget target) {
        return switch (target.dialect()) {
            case MYSQL -> new MySQLContainer(target.image()).withPassword(PASSWORD)
                .withTmpFs(Map.of(MYSQL_DATA_DIRECTORY, IN_MEMORY_MOUNT));
            case MARIADB -> new MariaDBContainer(target.image()).withPassword(PASSWORD)
                .withTmpFs(Map.of(MYSQL_DATA_DIRECTORY, IN_MEMORY_MOUNT));
            case POSTGRESQL -> new PostgreSQLContainer(target.image()).withUsername(ADMIN_USER).withPassword(PASSWORD)
                .withTmpFs(Map.of(POSTGRESQL_DATA_DIRECTORY, IN_MEMORY_MOUNT));
            case SQLITE -> throw new IllegalArgumentException("SQLite needs no container");
        };
    }

    private static String adminUrl(DialectTarget target, JdbcDatabaseContainer<?> container) {
        return databaseUrl(target, container, target.dialect() == DatabaseDialect.POSTGRESQL ? "postgres" : "");
    }

    private static String databaseUrl(DialectTarget target, JdbcDatabaseContainer<?> container, String database) {
        String scheme = switch (target.dialect()) {
            case MYSQL -> "jdbc:mysql://";
            case MARIADB -> "jdbc:mariadb://";
            case POSTGRESQL -> "jdbc:postgresql://";
            case SQLITE -> throw new IllegalArgumentException("SQLite has no server URL");
        };
        return scheme + container.getHost() + ":" + container.getFirstMappedPort() + "/" + database;
    }

    private static void execute(JdbcDatabaseContainer<?> container, String url, String sql) {
        try (Connection connection = connect(container, url);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not run " + sql + " on " + container.getDockerImageName(), e);
        }
    }
}
