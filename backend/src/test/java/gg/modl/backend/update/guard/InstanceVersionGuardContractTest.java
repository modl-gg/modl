package gg.modl.backend.update.guard;

import static gg.modl.backend.database.jooq.Tables.INSTANCE_STATE;
import static gg.modl.backend.database.jooq.Tables.SETTINGS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.ExpiringRowsConfiguration;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.WriteAttemptHook;
import gg.modl.backend.database.backup.BackupConfiguration;
import gg.modl.backend.database.backup.UpgradeBackups;
import gg.modl.backend.database.sql.DataSourceConfiguration;
import gg.modl.backend.database.sql.DatabaseMigration;
import gg.modl.backend.database.sql.FlywayConfiguration;
import gg.modl.backend.database.sql.JooqConfiguration;
import gg.modl.backend.database.sql.MaintenanceConfiguration;
import gg.modl.backend.database.sql.SqlDatabaseProperties;
import gg.modl.backend.database.sql.UnitOfWorkConfiguration;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.infrastructure.scheduling.ScheduledDatabaseJobs;
import gg.modl.backend.update.DeploymentDetector;
import gg.modl.backend.update.JacksonJsonMaps;
import gg.modl.backend.update.ReleaseBuild;
import gg.modl.backend.update.UpdateProperties;
import gg.modl.backend.update.commit.UpdateGate;
import gg.modl.backend.update.install.UpdateHosts;
import gg.modl.backend.support.MutableClock;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import gg.modl.update.ReleaseVersion;
import gg.modl.update.UpdateFiles;
import gg.modl.update.UpdatePhase;
import gg.modl.update.UpdateTransaction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class InstanceVersionGuardContractTest {
    private static final String OLD = "3.0.0";
    private static final String NEW = "3.0.1";

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock(SqlTestDatabase.START);
    private SqlDatabaseProperties database;
    private DataDirectoryProperties directory;

    @SqlContractTest
    void aFailedUpgradeIsUndoneByTheOldVersionWithTheDataFromBeforeIt(DialectTarget target) throws Exception {
        prepare(target);
        try (AnnotationConfigApplicationContext old = boot(OLD)) {
            assertThat(old.getBean(UpdateGate.class).closed()).isFalse();
            writeSetting(old, "kept");
        }
        try (AnnotationConfigApplicationContext upgraded = boot(NEW)) {
            assertThat(upgraded.getBean(UpdateGate.class).closed()).isTrue();
            assertThat(lastRunVersion(upgraded)).isEqualTo(NEW);
            writeSetting(upgraded, "written-by-the-failed-version");
        }
        UpdateTransaction unfinished = files().readPending();
        assertThat(unfinished.phase()).isEqualTo(UpdatePhase.STARTING);
        assertThat(unfinished.backup()).startsWith(UpgradeBackups.PREFIX);
        assertThat(directory.backups().resolve(unfinished.backup())).isRegularFile();

        try (AnnotationConfigApplicationContext rolledBack = boot(OLD)) {
            assertThat(rolledBack.getBean(UpdateGate.class).closed()).isFalse();
            assertThat(lastRunVersion(rolledBack)).isEqualTo(OLD);
            assertThat(settings(rolledBack)).containsExactly("kept");
        }
        assertThat(files().readPending()).isNull();
        assertThat(files().readLastFailure().restored()).isTrue();
    }

    @SqlContractTest
    void aRestartOfTheNewVersionResumesWithoutASecondBackup(DialectTarget target) throws Exception {
        prepare(target);
        boot(OLD).close();
        boot(NEW).close();
        String backup = files().readPending().backup();

        try (AnnotationConfigApplicationContext resumed = boot(NEW)) {
            assertThat(resumed.getBean(UpdateGate.class).closed()).isTrue();
        }

        assertThat(files().readPending().backup()).isEqualTo(backup);
        try (Stream<Path> archives = Files.list(directory.backups())) {
            assertThat(archives.filter(file -> file.getFileName().toString().startsWith(UpgradeBackups.PREFIX))).hasSize(1);
        }
    }

    @SqlContractTest
    void aFailedBackupLeavesTheDataAndTheVersionUntouched(DialectTarget target) throws Exception {
        prepare(target);
        try (AnnotationConfigApplicationContext old = boot(OLD)) {
            writeSetting(old, "kept");
        }
        Files.createDirectories(directory.backups().getParent());
        Files.writeString(directory.backups(), "a file where the folder should be");

        assertThatThrownBy(() -> boot(NEW).close()).hasStackTraceContaining("Nothing was changed");

        assertThat(files().readPending()).isNull();
        try (AnnotationConfigApplicationContext old = boot(OLD)) {
            assertThat(lastRunVersion(old)).isEqualTo(OLD);
            assertThat(settings(old)).containsExactly("kept");
        }
    }

    @SqlContractTest
    void anOlderVersionIsRefusedOnceTheUpdateCommitted(DialectTarget target) throws Exception {
        prepare(target);
        boot(OLD).close();
        boot(NEW).close();
        files().deletePending();

        assertThatThrownBy(() -> boot(OLD).close()).hasStackTraceContaining("which is newer than this modl");
    }

    @SqlContractTest
    void aDamagedBackupFailsClosed(DialectTarget target) throws Exception {
        prepare(target);
        boot(OLD).close();
        boot(NEW).close();
        Files.writeString(directory.backups().resolve(files().readPending().backup()), "damaged");

        assertThatThrownBy(() -> boot(OLD).close()).hasStackTraceContaining("missing or damaged");
        assertThat(files().readPending()).isNotNull();
    }

    private void prepare(DialectTarget target) {
        database = TestDialects.freshDatabase(target);
        directory = new DataDirectoryProperties(root.resolve("data").toString());
    }

    private UpdateFiles files() {
        return new UpdateFiles(directory.root(), JacksonJsonMaps.INSTANCE);
    }

    private AnnotationConfigApplicationContext boot(String version) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(SqlDatabaseProperties.class, () -> database);
        context.registerBean(DataDirectoryProperties.class, () -> directory);
        context.registerBean(OperatorNotice.class, OperatorNotice::new);
        context.registerBean(Clock.class, () -> clock);
        context.registerBean(WriteAttemptHook.class, () -> WriteAttemptHook.NONE);
        context.registerBean(UpdateFiles.class, this::files);
        context.registerBean(ReleaseBuild.class, () -> ReleaseBuild.of(ReleaseVersion.parse(version), ReleaseVersion.parse(OLD)));
        context.registerBean(UpdateProperties.class, UpdateProperties::defaults);
        context.registerBean(UpdateHosts.class, UpdateHosts::none);
        context.registerBean(DeploymentDetector.class, () -> new DeploymentDetector(DeploymentDetector.EMBEDDED));
        context.register(DataSourceConfiguration.class, FlywayConfiguration.class, JooqConfiguration.class,
            UnitOfWorkConfiguration.class, ExpiringRowsConfiguration.class, ScheduledDatabaseJobs.class, MaintenanceConfiguration.class,
            BackupConfiguration.class, UpgradeBackups.class, UpgradeRecovery.class, UpdateGate.class, StartupDeadline.class, UpdateOutcomes.class,
            InstanceVersionGuard.class);
        try {
            context.refresh();
            context.getBean(DatabaseMigration.class);
            return context;
        } catch (RuntimeException e) {
            context.close();
            throw e;
        }
    }

    private static void writeSetting(AnnotationConfigApplicationContext context, String type) {
        context.getBean(UnitOfWork.class).write(() -> {
            context.getBean(DSLContext.class).insertInto(SETTINGS)
                .set(SETTINGS.TYPE, type)
                .set(SETTINGS.DATA_JSON, "{}")
                .set(SETTINGS.VERSION, 1L)
                .set(SETTINGS.UPDATED_AT, SqlTestDatabase.START)
                .execute();
        });
    }

    private static List<String> settings(AnnotationConfigApplicationContext context) {
        return context.getBean(DSLContext.class).select(SETTINGS.TYPE).from(SETTINGS).fetch(SETTINGS.TYPE);
    }

    private static String lastRunVersion(AnnotationConfigApplicationContext context) {
        return context.getBean(DSLContext.class).select(INSTANCE_STATE.LAST_RUN_VERSION).from(INSTANCE_STATE)
            .fetchSingle(INSTANCE_STATE.LAST_RUN_VERSION);
    }
}
