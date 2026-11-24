package gg.modl.backend.database.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.sqlite.SQLiteDataSource;

class MigrationGateTest {
    private static final String MISSING_LOCATION = "db/migration/missing";
    private static final Set<String> TEST_OUTPUT_MARKERS = Set.of("classes/java/test", "resources/test");

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void anEmptyDatabaseMigratesToTheLatestVersion(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            DatabaseMigration migration = database.bean(DatabaseMigration.class);

            assertEquals(migrationFiles(target.dialect()).size(), migration.appliedMigrations());
            assertEquals(latestVersion(target.dialect()), migration.schemaVersion());
        }
    }

    @SqlContractTest
    void aSecondMigrateAppliesNothing(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            MigrateResult second = database.bean(Flyway.class).migrate();

            assertEquals(0, second.migrationsExecuted);
            assertTrue(second.success);
        }
    }

    @SqlContractTest
    void theMigratedSchemaValidates(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            assertTrue(database.bean(Flyway.class).validateWithResult().validationSuccessful);
        }
    }

    @SqlContractTest
    void aMissingMigrationLocationFailsInsteadOfMigratingNothing(DialectTarget target) {
        Flyway flyway = DatabaseMigration.configure(target.dialect(), emptyDatabase(target), MigrationGateTest.class.getClassLoader())
            .locations("classpath:" + MISSING_LOCATION)
            .load();

        FlywayException failure = assertThrows(FlywayException.class, flyway::migrate);

        assertTrue(failure.getMessage().contains(MISSING_LOCATION), failure.getMessage());
    }

    @SqlContractTest
    void anIsolatedServerClassLoaderResolvesEveryMigration(DialectTarget target) throws Exception {
        try (URLClassLoader server = isolatedServerClassLoader()) {
            Object flyway = isolatedFlyway(server, target.dialect(), emptyDatabase(target));
            Object pending = invoke(invoke(flyway, "info"), "pending");

            assertEquals(migrationFiles(target.dialect()).size(), Array.getLength(pending));
        }
    }

    @Test
    void everyDialectFolderHoldsTheSameMigrationFiles() {
        Map<String, Set<String>> files = new TreeMap<>();
        for (DatabaseDialect dialect : DatabaseDialect.values()) {
            files.put(dialect.migrationFolder(), migrationFiles(dialect));
        }

        assertEquals(1, files.values().stream().distinct().count(), files.toString());
        assertFalse(files.values().iterator().next().isEmpty(), files.toString());
    }

    private DataSource emptyDatabase(DialectTarget target) {
        if (target.isSqlite()) {
            SQLiteDataSource sqlite = new SQLiteDataSource();
            sqlite.setUrl("jdbc:sqlite:" + dataDirectory.resolve("empty-" + System.nanoTime() + ".db"));
            return sqlite;
        }
        return DriverDataSource.of(TestDialects.freshDatabase(target));
    }

    private static Object isolatedFlyway(ClassLoader server, DatabaseDialect dialect, DataSource dataSource) throws ReflectiveOperationException {
        Class<?> migration = server.loadClass(DatabaseMigration.class.getName());
        Class<?> isolatedDialect = server.loadClass(DatabaseDialect.class.getName());
        Object serverDialect = isolatedDialect.getMethod("valueOf", String.class).invoke(null, dialect.name());
        Object configuration = migration.getMethod("configure", isolatedDialect, DataSource.class, ClassLoader.class)
            .invoke(null, serverDialect, dataSource, server);
        return invoke(configuration, "load");
    }

    private static Object invoke(Object target, String method) throws ReflectiveOperationException {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(method + " failed in the isolated class loader", e.getCause());
        }
    }

    private static URLClassLoader isolatedServerClassLoader() {
        URL[] urls = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
            .filter(entry -> TEST_OUTPUT_MARKERS.stream().noneMatch(entry.replace(File.separatorChar, '/')::contains))
            .map(MigrationGateTest::url)
            .toArray(URL[]::new);
        return new URLClassLoader("isolated-server", urls, ClassLoader.getPlatformClassLoader());
    }

    private static URL url(String classPathEntry) {
        try {
            return Path.of(classPathEntry).toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalStateException(classPathEntry, e);
        }
    }

    private static Set<String> migrationFiles(DatabaseDialect dialect) {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:" + dialect.migrationLocation() + "/*");
            return Arrays.stream(resources).map(Resource::getFilename).filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String latestVersion(DatabaseDialect dialect) {
        return migrationFiles(dialect).stream()
            .map(file -> file.substring(1, file.indexOf("__")))
            .max(Comparator.comparing(MigrationGateTest::versionParts, MigrationGateTest::compareVersions))
            .orElseThrow();
    }

    private static List<Integer> versionParts(String version) {
        return Arrays.stream(version.split("[._]")).map(Integer::parseInt).toList();
    }

    private static int compareVersions(List<Integer> left, List<Integer> right) {
        for (int index = 0; index < Math.max(left.size(), right.size()); index++) {
            int difference = Integer.compare(index < left.size() ? left.get(index) : 0, index < right.size() ? right.get(index) : 0);
            if (difference != 0) {
                return difference;
            }
        }
        return 0;
    }
}
