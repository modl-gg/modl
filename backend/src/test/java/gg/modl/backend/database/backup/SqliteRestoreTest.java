package gg.modl.backend.database.backup;

import gg.modl.backend.support.sql.SqlTestDatabase;
import static gg.modl.backend.database.jooq.Tables.WEBAUTHN_CREDENTIALS;
import static gg.modl.backend.database.jooq.Tables.STAFF;
import static gg.modl.backend.database.jooq.Tables.PANEL_SESSIONS;
import static gg.modl.backend.database.jooq.Tables.SETTINGS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.ExpiringRowsConfiguration;
import gg.modl.backend.database.sql.DataSourceConfiguration;
import gg.modl.backend.database.sql.FlywayConfiguration;
import gg.modl.backend.database.sql.JooqConfiguration;
import gg.modl.backend.database.sql.MaintenanceConfiguration;
import gg.modl.backend.database.sql.SqlDatabaseProperties;
import gg.modl.backend.database.sql.UnitOfWorkConfiguration;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.infrastructure.scheduling.InstanceReadiness;
import gg.modl.backend.infrastructure.scheduling.ScheduledDatabaseJobs;
import gg.modl.backend.support.sql.TestDialects;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipOutputStream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.sqlite.SQLiteDataSource;

class SqliteRestoreTest {
    private static final String POOL_OPENING_STARTUP_CHECK = "durabilityAdvisor";

    @TempDir
    Path directory;

    @Test
    void aSnapshotRestoreLeavesNoStaleLogAndStartsInWalWithIncrementalAutoVacuum() throws Exception {
        BackupScenario scenario = BackupScenario.fresh(TestDialects.SQLITE, directory.resolve("data"));
        Map<String, List<String>> saved = scenario.seeded();
        Path archive = directory.resolve("snapshot.zip");
        scenario.write(ArchiveKind.SNAPSHOT, archive);

        scenario.restore(archive);

        Path database = scenario.dataDirectory().database().resolve("modl.db");
        assertThat(database.resolveSibling("modl.db-wal")).doesNotExist();
        assertThat(database.resolveSibling("modl.db-journal")).doesNotExist();
        assertThat(scenario.contents()).isEqualTo(saved);
        assertThat(query(database, "PRAGMA journal_mode")).isEqualTo("wal");
        assertThat(query(database, "PRAGMA auto_vacuum")).isEqualTo("2");
    }

    @Test
    void anArchivedFileWithoutAutoVacuumRestoresAsIncremental() throws Exception {
        BackupScenario scenario = BackupScenario.fresh(TestDialects.SQLITE, directory.resolve("data"));
        Map<String, List<String>> saved = scenario.seeded();
        Path archive = directory.resolve("snapshot.zip");
        scenario.write(ArchiveKind.SNAPSHOT, archive);
        Path full = Files.write(directory.resolve("full.db"), TestArchives.entry(archive, SnapshotArchiveSource.DATABASE_ENTRY));
        execute(full, "PRAGMA auto_vacuum = NONE", "VACUUM");
        assertThat(query(full, "PRAGMA auto_vacuum")).isEqualTo("0");
        byte[] converted = Files.readAllBytes(full);
        Path oldArchive = TestArchives.withEntry(archive, directory.resolve("old.zip"), SnapshotArchiveSource.DATABASE_ENTRY,
            bytes -> converted);

        scenario.restore(oldArchive);

        assertThat(query(scenario.dataDirectory().database().resolve("modl.db"), "PRAGMA auto_vacuum")).isEqualTo("2");
        assertThat(scenario.contents()).isEqualTo(saved);
    }

    @Test
    void aRestoreBringsBackNoSessionCodeOrSecondFactor() throws Exception {
        BackupScenario scenario = BackupScenario.fresh(TestDialects.SQLITE, directory.resolve("data"));
        Map<String, List<String>> saved = scenario.seeded();
        try (SqlTestDatabase running = scenario.start()) {
            running.unitOfWork().write(() -> {
                running.dsl().insertInto(PANEL_SESSIONS)
                    .set(PANEL_SESSIONS.ID_HASH, "a".repeat(64))
                    .set(PANEL_SESSIONS.EMAIL, "staff@example.com")
                    .set(PANEL_SESSIONS.SESSION_KEY_HASH, "key-hash")
                    .set(PANEL_SESSIONS.CREATED_AT, SqlTestDatabase.START)
                    .set(PANEL_SESSIONS.EXPIRES_AT, SqlTestDatabase.START.plusSeconds(86_400))
                    .execute();
                running.dsl().insertInto(STAFF)
                    .set(STAFF.ID, "staff-1")
                    .set(STAFF.EMAIL, "staff@example.com")
                    .set(STAFF.USERNAME, "staff")
                    .set(STAFF.TWO_FACTOR_SESSION_IP, "203.0.113.7")
                    .set(STAFF.TWO_FACTOR_SESSION_EXPIRES_AT, SqlTestDatabase.START.plusSeconds(86_400))
                    .set(STAFF.CREATED_AT, SqlTestDatabase.START)
                    .set(STAFF.UPDATED_AT, SqlTestDatabase.START)
                    .execute();
            });
        }
        Path archive = directory.resolve("with-sessions.zip");
        scenario.write(ArchiveKind.SNAPSHOT, archive);

        scenario.restore(archive);

        Map<String, List<String>> restored = scenario.contents();
        assertThat(restored.get(PANEL_SESSIONS.getName())).isEmpty();
        assertThat(restored.get(WEBAUTHN_CREDENTIALS.getName())).isEqualTo(saved.get(WEBAUTHN_CREDENTIALS.getName()));
        try (SqlTestDatabase running = scenario.start()) {
            assertThat(running.dsl().fetchSingle(STAFF).getTwoFactorSessionIp()).isNull();
            assertThat(running.dsl().fetchSingle(STAFF).getTwoFactorSessionExpiresAt()).isNull();
        }
    }

    @Test
    void aSnapshotWithATriggerIsRefused() throws Exception {
        BackupScenario scenario = BackupScenario.fresh(TestDialects.SQLITE, directory.resolve("data"));
        Map<String, List<String>> saved = scenario.seeded();
        Path archive = directory.resolve("snapshot.zip");
        scenario.write(ArchiveKind.SNAPSHOT, archive);
        Path altered = Files.write(directory.resolve("altered.db"), TestArchives.entry(archive, SnapshotArchiveSource.DATABASE_ENTRY));
        execute(altered, "CREATE TRIGGER planted AFTER INSERT ON modl_players BEGIN DELETE FROM modl_punishments; END");
        byte[] planted = Files.readAllBytes(altered);
        Path plantedArchive = TestArchives.withEntry(archive, directory.resolve("planted.zip"), SnapshotArchiveSource.DATABASE_ENTRY,
            bytes -> planted);

        assertThatThrownBy(() -> scenario.restore(plantedArchive)).hasMessageContaining("triggers");
        assertThat(scenario.contents()).isEqualTo(saved);
    }

    @Test
    void aCallerHoldingTheInstanceLockBacksUpAndRestoresBeforeThePoolsOpen() throws Exception {
        BackupScenario scenario = BackupScenario.fresh(TestDialects.SQLITE, directory.resolve("data"));
        scenario.seeded();
        Path archive = directory.resolve("held-lock.zip");
        try (AnnotationConfigApplicationContext context = lazyServer(scenario, starting -> backUpAndRestore(starting, archive))) {
            DatabaseRestore restore = context.getBean(DatabaseRestore.class);

            assertThat(context.getBean(DSLContext.class).fetchCount(SETTINGS)).isEqualTo(1);
            assertThat(query(scenario.dataDirectory().database().resolve("modl.db"), "PRAGMA journal_mode")).isEqualTo("wal");
            assertThatThrownBy(() -> restore.open(archive)).hasMessageContaining("no open connections");
        }
    }

    private static void backUpAndRestore(AnnotationConfigApplicationContext starting, Path archive) {
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                starting.getBean(DatabaseBackup.class).write(ArchiveKind.LOGICAL, zip);
            }
            try (RestorableArchive verified = starting.getBean(DatabaseRestore.class).open(archive)) {
                verified.restore();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(starting.getDefaultListableBeanFactory().containsSingleton("connectionPools")).isFalse();
    }

    private static AnnotationConfigApplicationContext lazyServer(BackupScenario scenario,
                                                                 Consumer<AnnotationConfigApplicationContext> beforePoolsOpen) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(SqlDatabaseProperties.class, scenario::database);
        context.registerBean(DataDirectoryProperties.class, scenario::dataDirectory);
        context.registerBean(OperatorNotice.class, OperatorNotice::new);
        context.registerBean(Clock.class, () -> BackupScenario.CLOCK);
        context.registerBean(InstanceReadiness.class, () -> SqlTestDatabase.COMMITTED);
        context.register(DataSourceConfiguration.class, FlywayConfiguration.class, JooqConfiguration.class,
            UnitOfWorkConfiguration.class, ExpiringRowsConfiguration.class, ScheduledDatabaseJobs.class, MaintenanceConfiguration.class,
            BackupConfiguration.class);
        context.addBeanFactoryPostProcessor(factory -> factory.getBeanDefinition(POOL_OPENING_STARTUP_CHECK).setLazyInit(true));
        context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor());
        context.registerBean(SmartInitializingSingleton.class, () -> () -> beforePoolsOpen.accept(context));
        context.refresh();
        return context;
    }

    private static void execute(Path database, String... statements) throws SQLException {
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.executeUpdate(sql);
            }
        }
    }

    private static String query(Path database, String sql) throws SQLException {
        try (Connection connection = connect(database); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : "";
        }
    }

    private static Connection connect(Path database) throws SQLException {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + database);
        return source.getConnection();
    }
}
