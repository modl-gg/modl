package gg.modl.backend.support.sql;

import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.sql.SqlDatabaseProperties;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
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

    private static final List<DialectTarget> ALL = List.of(SQLITE, MYSQL, MARIADB, POSTGRESQL, MARIADB_LATEST, POSTGRESQL_LATEST);
    private static final String REQUIRE_ALL = "MODL_REQUIRE_ALL_DIALECTS";
    private static final String SELECTED = "MODL_TEST_DIALECTS";
    private static final String ADMIN_USER = "root";
    private static final String PASSWORD = "modl-test";
    private static final String MYSQL_DATA_DIRECTORY = "/var/lib/mysql";
    private static final String POSTGRESQL_DATA_DIRECTORY = "/var/lib/postgresql/data";
    private static final String IN_MEMORY_MOUNT = "rw";
    private static final Map<String, JdbcDatabaseContainer<?>> CONTAINERS = new ConcurrentHashMap<>();
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final String DATABASE_PREFIX = "modl_test_" + ProcessHandle.current().pid() + "_"
        + startSecond(ProcessHandle.current()).orElseGet(() -> System.currentTimeMillis() / 1000) + "_";
    private static final Pattern OWNED_DATABASE = Pattern.compile("modl_test_(\\d+)_(\\d+)_\\d+");
    private static volatile @Nullable List<DialectTarget> selection;

    private TestDialects() {
    }

    public static List<DialectTarget> available() {
        List<DialectTarget> targets = selection;
        if (targets == null) {
            targets = requireDocker(selected());
            selection = targets;
        }
        return targets;
    }

    public static DialectTarget named(String name) {
        return ALL.stream()
            .filter(target -> target.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown test dialect " + name + ", expected one of " + ALL));
    }

    public static Stream<DialectTarget> availableStream() {
        return available().stream();
    }

    public static List<DialectTarget> external() {
        return available().stream().filter(target -> !target.isSqlite()).toList();
    }

    public static SqlDatabaseProperties freshDatabase(DialectTarget target) {
        if (target.isSqlite()) {
            return new SqlDatabaseProperties(null, null, null, null, null);
        }
        String name = newDatabaseName();
        execute(target, "CREATE DATABASE " + name);
        return database(target, name);
    }

    static String newDatabaseName() {
        return DATABASE_PREFIX + DATABASES.incrementAndGet();
    }

    static SqlDatabaseProperties database(DialectTarget target, String name) {
        return new SqlDatabaseProperties(databaseUrl(target, container(target), name), ADMIN_USER, PASSWORD, 4, null);
    }

    static String databaseName(SqlDatabaseProperties database) {
        String url = database.externalUrl().orElseThrow();
        return url.substring(url.lastIndexOf('/') + 1);
    }

    static void drop(DialectTarget target, SqlDatabaseProperties database) {
        if (database.externalUrl().isPresent()) {
            execute(target, dropDatabase(target, databaseName(database)));
        }
    }

    public static void execute(DialectTarget target, String sql) {
        JdbcDatabaseContainer<?> container = container(target);
        execute(container, adminUrl(target, container), sql);
    }

    public static void executeIn(DialectTarget target, SqlDatabaseProperties database, String sql) {
        execute(container(target), database.externalUrl().orElseThrow(), sql);
    }

    static Connection connect(DialectTarget target, SqlDatabaseProperties database) throws SQLException {
        return connect(container(target), database.externalUrl().orElseThrow());
    }

    private static Connection connect(JdbcDatabaseContainer<?> container, String url) throws SQLException {
        Properties credentials = new Properties();
        credentials.setProperty("user", ADMIN_USER);
        credentials.setProperty("password", PASSWORD);
        return container.getJdbcDriverInstance().connect(url, credentials);
    }

    private static List<DialectTarget> selected() {
        if (Boolean.parseBoolean(System.getenv(REQUIRE_ALL))) {
            return ALL;
        }
        String names = System.getenv(SELECTED);
        if (names == null || names.isBlank()) {
            return List.of(SQLITE);
        }
        return Stream.of(names.split(",")).map(String::trim).filter(name -> !name.isEmpty()).distinct()
            .map(TestDialects::named).toList();
    }

    private static List<DialectTarget> requireDocker(List<DialectTarget> targets) {
        if (targets.stream().anyMatch(target -> !target.isSqlite()) && !DockerClientFactory.instance().isDockerAvailable()) {
            throw new IllegalStateException("The test dialects " + targets + " need Docker, but Docker is not available");
        }
        return targets;
    }

    private static JdbcDatabaseContainer<?> container(DialectTarget target) {
        return CONTAINERS.computeIfAbsent(target.name(), name -> {
            JdbcDatabaseContainer<?> container = ContainerStarts.start(newContainer(target));
            dropDatabasesOfEndedProcesses(target, container);
            return container;
        });
    }

    private static void dropDatabasesOfEndedProcesses(DialectTarget target, JdbcDatabaseContainer<?> container) {
        String adminUrl = adminUrl(target, container);
        String list = target.dialect() == DatabaseDialect.POSTGRESQL ? "SELECT datname FROM pg_database" : "SHOW DATABASES";
        for (String name : strings(container, adminUrl, list)) {
            if (ownerEnded(name)) {
                execute(container, adminUrl, dropDatabase(target, name));
            }
        }
    }

    private static String dropDatabase(DialectTarget target, String name) {
        return "DROP DATABASE " + name + (target.dialect() == DatabaseDialect.POSTGRESQL ? " WITH (FORCE)" : "");
    }

    private static boolean ownerEnded(String database) {
        Matcher owner = OWNED_DATABASE.matcher(database);
        if (!owner.matches() || database.startsWith(DATABASE_PREFIX)) {
            return false;
        }
        long startSecond = Long.parseLong(owner.group(2));
        return ProcessHandle.of(Long.parseLong(owner.group(1)))
            .map(process -> startSecond(process).map(started -> started != startSecond).orElse(false))
            .orElse(true);
    }

    private static Optional<Long> startSecond(ProcessHandle process) {
        return process.info().startInstant().map(Instant::getEpochSecond);
    }

    private static List<String> strings(JdbcDatabaseContainer<?> container, String url, String sql) {
        try (Connection connection = connect(container, url);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not run " + sql + " on " + container.getDockerImageName(), e);
        }
    }

    private static JdbcDatabaseContainer<?> newContainer(DialectTarget target) {
        return switch (target.dialect()) {
            case MYSQL -> new MySQLContainer(target.image()).withPassword(PASSWORD)
                .withTmpFs(Map.of(MYSQL_DATA_DIRECTORY, IN_MEMORY_MOUNT)).withReuse(true);
            case MARIADB -> new MariaDBContainer(target.image()).withPassword(PASSWORD)
                .withTmpFs(Map.of(MYSQL_DATA_DIRECTORY, IN_MEMORY_MOUNT)).withReuse(true);
            case POSTGRESQL -> new PostgreSQLContainer(target.image()).withUsername(ADMIN_USER).withPassword(PASSWORD)
                .withTmpFs(Map.of(POSTGRESQL_DATA_DIRECTORY, IN_MEMORY_MOUNT)).withReuse(true);
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
