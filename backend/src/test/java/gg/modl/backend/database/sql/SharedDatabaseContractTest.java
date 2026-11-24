package gg.modl.backend.database.sql;

import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.backup.BackupDiskSpace;
import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.WriteAttemptHook;
import gg.modl.backend.database.backup.ArchiveKind;
import gg.modl.backend.database.backup.ArchivingDatabaseBackup;
import gg.modl.backend.database.backup.BackupTestData;
import gg.modl.backend.database.backup.DatabaseRestore;
import gg.modl.backend.database.backup.RestorableArchive;
import gg.modl.backend.infrastructure.disk.DiskSpaceFloor;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

class SharedDatabaseContractTest {
    private static final BackupDiskSpace UNLIMITED = new BackupDiskSpace(new DiskSpaceFloor(0, 0), Files::getFileStore);
    private static final ClassLoader THIS_JAR = SharedDatabaseContractTest.class.getClassLoader();
    private static final String LATER_TABLE = "modl_later_feature";
    private static final String LATER_TABLE_DDL = "CREATE TABLE " + LATER_TABLE + " (id VARCHAR(36) NOT NULL PRIMARY KEY);\n";

    @TempDir
    Path directory;

    private SqlDatabaseProperties database;
    private DataDirectoryProperties dataDirectory;
    private SharedDatabase shared;

    private void use(DialectTarget target) {
        database = TestDialects.freshDatabase(target);
        dataDirectory = new DataDirectoryProperties(directory.resolve("data").toString());
        shared = new SharedDatabase(database, dataDirectory);
    }

    @SqlContractTest
    void aBackupArchivesOnlyModlsTablesAndLeavesTheOthersAlone(DialectTarget target) throws Exception {
        use(target);
        seed(target);
        shared.addOtherPluginsTable();

        Path archive = logicalBackup(database, dataDirectory, THIS_JAR);

        assertThat(entryNames(archive)).noneMatch(name -> name.contains(SharedDatabase.OTHER_TABLE));
        assertThat(manifest(archive)).doesNotContain(SharedDatabase.OTHER_TABLE);
        assertThat(shared.otherPluginsRows()).isEqualTo(SharedDatabase.OTHER_ROWS);
    }

    @SqlContractTest
    void aRestoreReplacesModlsTablesAndLeavesTheOthersAlone(DialectTarget target) throws Exception {
        use(target);
        Map<String, List<String>> saved = seed(target);
        shared.addOtherPluginsTable();
        Path archive = logicalBackup(database, dataDirectory, THIS_JAR);
        addPlayer(target, "added-after-the-backup");

        restore(archive, THIS_JAR, Set.of());

        assertThat(shared.otherPluginsRows()).isEqualTo(SharedDatabase.OTHER_ROWS);
        assertThat(contents(target)).isEqualTo(saved);
    }

    @SqlContractTest
    void aRestoreThatFailsWhileLoadingLeavesTheOthersAloneAndCanBeRunAgain(DialectTarget target) throws Exception {
        use(target);
        Map<String, List<String>> saved = seed(target);
        shared.addOtherPluginsTable();
        Path archive = logicalBackup(database, dataDirectory, THIS_JAR);

        try (LockedDatabase locked = LockedDatabase.lock(database, dataDirectory, THIS_JAR)) {
            RestoreTarget restoreTarget = locked.target();
            SqliteFile rejected = restoreTarget.stagingFile(directory.resolve("rejected.db"));
            rejected.create("1");
            SqliteFileRows.addTicketTheSchemaRejects(rejected.path());

            assertThatThrownBy(() -> restoreTarget.replaceWith(rejected, Set.of())).isInstanceOf(DatabaseToolException.class);
        }
        assertThat(shared.otherPluginsRows()).isEqualTo(SharedDatabase.OTHER_ROWS);
        if (target.isSqlite()) {
            assertThat(contents(target)).isEqualTo(saved);
        } else {
            assertThat(shared.hasTable(ModlSchema.UNFINISHED_TABLE)).isTrue();
            assertThatThrownBy(() -> start(target)).hasStackTraceContaining(DatabaseMigration.UNFINISHED_RESTORE);
        }

        restore(archive, THIS_JAR, Set.of());

        assertThat(shared.otherPluginsRows()).isEqualTo(SharedDatabase.OTHER_ROWS);
        assertThat(shared.hasTable(ModlSchema.UNFINISHED_TABLE)).isFalse();
        assertThat(shared.hasTable(ModlSchema.RESTORE_TABLES)).isFalse();
        assertThat(contents(target)).isEqualTo(saved);
    }

    @SqlContractTest
    void aCopyGoesBesideTheTablesOfOtherSoftware(DialectTarget target) throws Exception {
        use(target);
        DataDirectoryProperties sourceDirectory = new DataDirectoryProperties(directory.resolve("source").toString());
        SqlDatabaseProperties source = TestDialects.freshDatabase(TestDialects.SQLITE);
        Map<String, List<String>> saved;
        try (SqlTestDatabase running = SqlTestDatabase.start(TestDialects.SQLITE, sourceDirectory.root())) {
            BackupTestData.seed(running);
            saved = BackupTestData.contents(running);
        }
        Path archive = logicalBackup(source, sourceDirectory, THIS_JAR);
        Files.createDirectories(dataDirectory.database());
        shared.addOtherPluginsTable();
        try (LockedDatabase locked = LockedDatabase.lock(database, dataDirectory, THIS_JAR)) {
            assertThat(locked.target().modlTables()).isEmpty();
        }

        restore(archive, THIS_JAR, Set.of());

        assertThat(shared.otherPluginsRows()).isEqualTo(SharedDatabase.OTHER_ROWS);
        assertThat(contents(target)).isEqualTo(saved);
    }

    @SqlContractTest
    void aSnapshotNeverBringsBackAnOlderCopyOfAnotherPluginsTable(DialectTarget target) throws Exception {
        Assumptions.assumeTrue(target.isSqlite());
        use(target);
        seed(target);
        shared.addOtherPluginsTable();
        Path archive = backup(database, dataDirectory, THIS_JAR, ArchiveKind.SNAPSHOT);
        shared.execute("INSERT INTO " + SharedDatabase.OTHER_TABLE + " VALUES (3, 7.25, 'carol')");
        List<String> current = shared.otherPluginsRows();

        restore(archive, THIS_JAR, Set.of());

        assertThat(shared.otherPluginsRows()).isEqualTo(current);
    }

    @SqlContractTest
    void anotherPluginsTableWithAModlNameIsNeverTakenForModls(DialectTarget target) throws Exception {
        use(target);
        Path archive = emptyModlArchive();
        Files.createDirectories(dataDirectory.database());
        shared.addOtherPluginsTable();
        shared.execute("CREATE TABLE " + PLAYERS.getName() + " (name VARCHAR(16))",
            "INSERT INTO " + PLAYERS.getName() + " VALUES ('Notch')");

        assertThatThrownBy(() -> restore(archive, THIS_JAR, Set.of()))
            .hasMessageContaining("Nothing was changed")
            .hasMessageContaining(PLAYERS.getName());

        assertThat(shared.query("SELECT name FROM " + PLAYERS.getName())).containsExactly("Notch");
        assertThat(shared.otherPluginsRows()).isEqualTo(SharedDatabase.OTHER_ROWS);
        assertThat(shared.hasTable(PUNISHMENTS.getName())).isFalse();
        assertThat(shared.hasTable(ModlSchema.UNFINISHED_TABLE)).isFalse();
    }

    @SqlContractTest
    void anotherApplicationsTableNamedLikeModlsHistoryIsNeverTakenForModls(DialectTarget target) throws Exception {
        use(target);
        Path archive = emptyModlArchive();
        Files.createDirectories(dataDirectory.database());
        shared.execute("CREATE TABLE " + ModlSchema.HISTORY_TABLE + " (installed_rank INTEGER NOT NULL PRIMARY KEY,"
                + " version VARCHAR(50), checksum INTEGER, success BOOLEAN NOT NULL)",
            "INSERT INTO " + ModlSchema.HISTORY_TABLE + " VALUES (1, '1', 4242, TRUE)");

        assertThatThrownBy(() -> restore(archive, THIS_JAR, Set.of()))
            .hasMessageContaining("Nothing was changed")
            .hasMessageContaining(ModlSchema.HISTORY_TABLE);

        assertThat(shared.query("SELECT checksum FROM " + ModlSchema.HISTORY_TABLE)).containsExactly("4242");
        assertThat(shared.hasTable(PUNISHMENTS.getName())).isFalse();
    }

    @SqlContractTest
    void aCopyBesideAnotherApplicationsFlywayHistoryAndLookalikeTablesLeavesThemAlone(DialectTarget target) throws Exception {
        use(target);
        Path archive = emptyModlArchive();
        Files.createDirectories(dataDirectory.database());
        shared.addLookalikeTables();

        restore(archive, THIS_JAR, Set.of());

        assertThat(shared.lookalikeRows()).isEqualTo(SharedDatabase.LOOKALIKE_ROWS);
        assertThat(shared.hasTable(PUNISHMENTS.getName())).isTrue();
        assertThat(shared.hasTable(ModlSchema.HISTORY_TABLE)).isTrue();
    }

    @SqlContractTest
    void aRestoreNeverArchivesOrDropsALookalikeTableOfAnotherApplication(DialectTarget target) throws Exception {
        use(target);
        Map<String, List<String>> saved = seed(target);
        shared.addLookalikeTables();
        Path archive = logicalBackup(database, dataDirectory, THIS_JAR);
        addPlayer(target, "added-after-the-backup");

        restore(archive, THIS_JAR, Set.of());

        assertThat(entryNames(archive)).doesNotContainAnyElementsOf(SharedDatabase.LOOKALIKE_ROWS.keySet().stream()
            .map(table -> "tables/" + table + ".jsonl")
            .toList());
        assertThat(shared.lookalikeRows()).isEqualTo(SharedDatabase.LOOKALIKE_ROWS);
        assertThat(contents(target)).isEqualTo(saved);
    }

    @SqlContractTest
    void aForeignKeyFromAnotherTableRefusesTheRestoreBeforeAnyChange(DialectTarget target) throws Exception {
        use(target);
        seed(target);
        Path archive = logicalBackup(database, dataDirectory, THIS_JAR);
        addPlayer(target, "kept");
        shared.execute("CREATE TABLE otherplugin_homes (player_id " + shared.playerIdColumn()
                + ", CONSTRAINT fk_otherplugin_homes_player FOREIGN KEY (player_id) REFERENCES " + PLAYERS.getName() + " (id))",
            "INSERT INTO otherplugin_homes VALUES ('kept')");

        assertThatThrownBy(() -> restore(archive, THIS_JAR, Set.of()))
            .hasMessageContaining("Nothing was changed")
            .hasMessageContaining("otherplugin_homes");

        assertThat(shared.query("SELECT player_id FROM otherplugin_homes")).containsExactly("kept");
        assertThat(shared.query("SELECT id FROM " + PLAYERS.getName() + " WHERE id = 'kept'")).containsExactly("kept");
    }

    @SqlContractTest
    void aPostgresqlViewOnModlsTablesRefusesTheRestoreBeforeAnyChange(DialectTarget target) throws Exception {
        Assumptions.assumeTrue(target.name().startsWith("postgresql"));
        use(target);
        seed(target);
        Path archive = logicalBackup(database, dataDirectory, THIS_JAR);
        shared.execute("CREATE VIEW otherplugin_names AS SELECT username FROM " + PLAYERS.getName());

        assertThatThrownBy(() -> restore(archive, THIS_JAR, Set.of())).hasMessageContaining("otherplugin_names");

        assertThat(shared.query("SELECT count(*) FROM otherplugin_names")).containsExactly(String.valueOf(BackupTestData.PLAYER_COUNT));
    }

    @SqlContractTest
    void laterMigrationsAreOwnedWithoutArchiverEditsAndARollbackRemovesOnlyTheTablesItRecorded(DialectTarget target) throws Exception {
        use(target);
        Map<String, List<String>> saved = seed(target);
        Path beforeUpgrade = logicalBackup(database, dataDirectory, THIS_JAR);
        try (URLClassLoader laterJar = jarWithASecondMigration()) {
            try (LockedDatabase locked = LockedDatabase.lock(database, dataDirectory, laterJar)) {
                locked.target().upgradeSchema(SqlTestDatabase.START);
            }
            shared.addOtherPluginsTable();
            shared.execute("INSERT INTO " + LATER_TABLE + " VALUES ('kept-in-archive')");
            Path upgraded = logicalBackup(database, dataDirectory, laterJar);

            assertThat(entryNames(upgraded)).contains("tables/" + LATER_TABLE + ".jsonl")
                .noneMatch(name -> name.contains(SharedDatabase.OTHER_TABLE));
            shared.execute("DELETE FROM " + LATER_TABLE);
            restore(upgraded, laterJar, Set.of());
            assertThat(shared.query("SELECT id FROM " + LATER_TABLE)).containsExactly("kept-in-archive");
            assertThat(shared.otherPluginsRows()).isEqualTo(SharedDatabase.OTHER_ROWS);
        }

        assertThatThrownBy(() -> restore(beforeUpgrade, THIS_JAR, Set.of()))
            .hasMessageContaining("Nothing was changed")
            .hasMessageContaining(LATER_TABLE);
        assertThat(shared.query("SELECT id FROM " + LATER_TABLE)).containsExactly("kept-in-archive");

        restore(beforeUpgrade, THIS_JAR, Set.of(LATER_TABLE));

        assertThat(shared.hasTable(LATER_TABLE)).isFalse();
        assertThat(shared.otherPluginsRows()).isEqualTo(SharedDatabase.OTHER_ROWS);
        assertThat(contents(target)).isEqualTo(saved);
    }

    @SqlContractTest
    void aRollbackRemovesATableAFailedUpgradeLeftWithoutRecordingItsMigration(DialectTarget target) throws Exception {
        use(target);
        Map<String, List<String>> saved = seed(target);
        Path beforeUpgrade = logicalBackup(database, dataDirectory, THIS_JAR);
        shared.addOtherPluginsTable();
        shared.execute("CREATE TABLE " + LATER_TABLE + " (id VARCHAR(16))");

        restore(beforeUpgrade, THIS_JAR, Set.of(LATER_TABLE));

        assertThat(shared.hasTable(LATER_TABLE)).isFalse();
        assertThat(shared.otherPluginsRows()).isEqualTo(SharedDatabase.OTHER_ROWS);
        assertThat(contents(target)).isEqualTo(saved);
    }

    @SqlContractTest
    void aRetriedRestoreNeverDropsATableAnotherPluginCreatedAfterTheFirstRunFailedBeforeModlCreatedIt(DialectTarget target)
        throws Exception {
        Assumptions.assumeFalse(target.isSqlite());
        use(target);
        seed(target);
        Path upgraded;
        try (URLClassLoader laterJar = jarWithASecondMigration()) {
            try (LockedDatabase locked = LockedDatabase.lock(database, dataDirectory, laterJar)) {
                locked.target().upgradeSchema(SqlTestDatabase.START);
            }
            upgraded = logicalBackup(database, dataDirectory, laterJar);
        }
        try (URLClassLoader failingJar = jarWhoseSecondMigrationFailsOnExternalDatabases()) {
            assertThatThrownBy(() -> restore(upgraded, failingJar, Set.of())).isNotNull();
            assertThat(shared.hasTable(LATER_TABLE)).isFalse();
            shared.execute("CREATE TABLE " + LATER_TABLE + " (note VARCHAR(16))", "INSERT INTO " + LATER_TABLE + " VALUES ('theirs')");

            assertThatThrownBy(() -> restore(upgraded, failingJar, Set.of()))
                .hasMessageContaining("Nothing was changed")
                .hasMessageContaining(LATER_TABLE);
        }

        assertThat(shared.query("SELECT note FROM " + LATER_TABLE)).containsExactly("theirs");
    }

    @SqlContractTest
    void aRetriedRestoreReplacesTheTablesAMigrationCreatedBeforeItFailed(DialectTarget target) throws Exception {
        Assumptions.assumeFalse(target.isSqlite());
        use(target);
        seed(target);
        Path upgraded;
        try (URLClassLoader laterJar = jarWithASecondMigration()) {
            try (LockedDatabase locked = LockedDatabase.lock(database, dataDirectory, laterJar)) {
                locked.target().upgradeSchema(SqlTestDatabase.START);
            }
            shared.execute("INSERT INTO " + LATER_TABLE + " VALUES ('kept-in-archive')");
            upgraded = logicalBackup(database, dataDirectory, laterJar);
        }
        try (URLClassLoader failingJar = jarWhoseSecondMigrationFailsAfterCreatingItsTable()) {
            assertThatThrownBy(() -> restore(upgraded, failingJar, Set.of())).isNotNull();
        }

        try (URLClassLoader laterJar = jarWithASecondMigration()) {
            restore(upgraded, laterJar, Set.of());
        }

        assertThat(shared.query("SELECT id FROM " + LATER_TABLE)).containsExactly("kept-in-archive");
    }

    private Path emptyModlArchive() throws Exception {
        DataDirectoryProperties sourceDirectory = new DataDirectoryProperties(directory.resolve("source").toString());
        SqlTestDatabase.start(TestDialects.SQLITE, sourceDirectory.root()).close();
        return logicalBackup(TestDialects.freshDatabase(TestDialects.SQLITE), sourceDirectory, THIS_JAR);
    }

    private SqlTestDatabase start(DialectTarget target) {
        return SqlTestDatabase.start(target, dataDirectory.root(), database, WriteAttemptHook.NONE);
    }

    private Map<String, List<String>> seed(DialectTarget target) {
        try (SqlTestDatabase running = start(target)) {
            BackupTestData.seed(running);
            return BackupTestData.contents(running);
        }
    }

    private Map<String, List<String>> contents(DialectTarget target) {
        try (SqlTestDatabase running = start(target)) {
            return BackupTestData.contents(running);
        }
    }

    private void addPlayer(DialectTarget target, String id) {
        try (SqlTestDatabase running = start(target)) {
            running.unitOfWork().write(() -> {
                running.dsl().insertInto(PLAYERS).set(BackupTestData.player(id, false)).execute();
            });
        }
    }

    private Path logicalBackup(SqlDatabaseProperties source, DataDirectoryProperties sourceDirectory, ClassLoader jar) throws Exception {
        return backup(source, sourceDirectory, jar, ArchiveKind.LOGICAL);
    }

    private Path backup(SqlDatabaseProperties source, DataDirectoryProperties sourceDirectory, ClassLoader jar, ArchiveKind kind)
        throws Exception {
        Path archive = Files.createTempFile(directory, "backup-", ".zip");
        try (OfflineDatabase offline = OfflineDatabase.open(source, sourceDirectory, jar);
             ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            new ArchivingDatabaseBackup(offline.source(), sourceDirectory.temporary(), UNLIMITED, Clock.systemUTC()).write(kind, zip);
        }
        return archive;
    }

    private void restore(Path archive, ClassLoader jar, Set<String> newerVersionTables) throws Exception {
        try (LockedDatabase locked = LockedDatabase.lock(database, dataDirectory, jar)) {
            DatabaseRestore restore = new DatabaseRestore(locked.target(), UNLIMITED);
            try (RestorableArchive verified = restore.open(archive, newerVersionTables)) {
                verified.restore();
            }
        }
    }

    private static Set<String> entryNames(Path archive) throws Exception {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            return zip.stream().map(ZipEntry::getName).collect(Collectors.toSet());
        }
    }

    private static String manifest(Path archive) throws Exception {
        try (ZipFile zip = new ZipFile(archive.toFile()); InputStream input = zip.getInputStream(zip.getEntry("manifest.json"))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private URLClassLoader jarWithASecondMigration() throws Exception {
        return jarWithSecondMigrations("later-jar", LATER_TABLE_DDL, LATER_TABLE_DDL);
    }

    private URLClassLoader jarWhoseSecondMigrationFailsOnExternalDatabases() throws Exception {
        return jarWithSecondMigrations("failing-jar", LATER_TABLE_DDL, "INSERT INTO no_such_table VALUES (1);\n" + LATER_TABLE_DDL);
    }

    private URLClassLoader jarWhoseSecondMigrationFailsAfterCreatingItsTable() throws Exception {
        return jarWithSecondMigrations("half-done-jar", LATER_TABLE_DDL, LATER_TABLE_DDL + "INSERT INTO no_such_table VALUES (1);\n");
    }

    private URLClassLoader jarWithSecondMigrations(String name, String sqlite, String external) throws Exception {
        Path jar = directory.resolve(name);
        for (String folder : List.of("sqlite", "mysql", "postgresql")) {
            Path migrations = Files.createDirectories(jar.resolve("db/migration").resolve(folder));
            Files.writeString(migrations.resolve("V999__later_feature.sql"), "sqlite".equals(folder) ? sqlite : external);
        }
        return new URLClassLoader(new URL[] {jar.toUri().toURL()}, THIS_JAR);
    }
}
