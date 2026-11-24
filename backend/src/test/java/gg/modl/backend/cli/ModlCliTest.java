package gg.modl.backend.cli;

import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.SETTINGS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import gg.modl.backend.backup.InterruptedRestoreNotice;
import gg.modl.backend.backup.UploadsRestoreMarker;
import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.WriteAttemptHook;
import gg.modl.backend.database.backup.BackupTestData;
import gg.modl.backend.database.sql.SqlDatabaseProperties;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.storage.local.LocalObjectStorage;
import gg.modl.backend.storage.local.UploadSigner;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ModlCliTest {
    private static final String PASSWORD_VARIABLE = "COPY_TARGET_PASSWORD";

    @TempDir
    Path dataDirectory;

    private Map<String, Object> environment;

    @BeforeEach
    void setUp() {
        environment = Map.of(
            "modl.data-dir", dataDirectory.toString(),
            "modl.database.url", "",
            "MODL_STORAGE", ""
        );
    }

    @Test
    void recognisesOnlyItsSubcommands() {
        assertThat(ModlCli.handles(new String[] {"backup", "--output", "x.zip"})).isTrue();
        assertThat(ModlCli.handles(new String[] {"copy-database"})).isTrue();
        assertThat(ModlCli.handles(new String[] {"--server.port=8080"})).isFalse();
        assertThat(ModlCli.handles(new String[0])).isFalse();
    }

    @Test
    void unknownAndRemovedCommandsPrintUsageInsteadOfStartingTheServer() {
        assertThat(ModlCli.handles(new String[] {"backpu"})).isTrue();
        assertThat(run("backpu")).isEqualTo(2);
        assertThat(run("import-database")).isEqualTo(2);
    }

    @Test
    void backupAndRestoreRoundTripTheBuiltInDatabase() throws IOException {
        addPlayers("a", "b");
        Path archive = archive();

        assertThat(run("backup", "--output", archive.toString())).isZero();
        addPlayers("c");
        assertThat(run("restore", "--input", archive.toString())).isZero();

        assertThat(playerIds()).containsExactly("a", "b");
        assertThat(backupNames()).singleElement().satisfies(name -> assertThat(name).startsWith("pre-restore-").endsWith(".zip"));
    }

    @Test
    void aDamagedArchiveIsRejectedBeforeAnySafetyBackupIsWritten() throws IOException {
        addPlayers("kept");
        Path archive = dataDirectory.resolve("not-a-backup.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("readme.txt"));
            zip.closeEntry();
        }

        assertThat(run("restore", "--input", archive.toString())).isEqualTo(1);
        assertThat(dataDirectory.resolve("backups")).doesNotExist();
        assertThat(playerIds()).containsExactly("kept");
    }

    @Test
    void backupRunsBesideAServerButRestoreIsRefusedWhileTheLockIsHeld() {
        Path archive = archive();
        try (SqlTestDatabase running = startDatabase()) {
            addPlayer(running, "live");

            assertThat(run("backup", "--output", archive.toString())).isZero();
            addPlayer(running, "after-backup");
            assertThat(run("restore", "--input", archive.toString())).isEqualTo(1);

            assertThat(running.dsl().fetchCount(PLAYERS)).isEqualTo(2);
        }
    }

    @Test
    void aBackupWithoutADatabaseFailsAndCreatesNone() {
        assertThat(run("backup", "--output", archive().toString())).isEqualTo(1);
        assertThat(dataDirectory.resolve("database/modl.db")).doesNotExist();
    }

    @Test
    void aRestoreIntoAnEmptyDataDirectoryNeedsNoSafetyBackup() throws IOException {
        addPlayers("a");
        Path archive = archive();
        assertThat(run("backup", "--output", archive.toString())).isZero();
        try (Stream<Path> files = Files.list(dataDirectory.resolve("database"))) {
            for (Path file : files.filter(path -> path.getFileName().toString().startsWith("modl.db")).toList()) {
                Files.delete(file);
            }
        }

        assertThat(run("restore", "--input", archive.toString())).isZero();

        assertThat(playerIds()).containsExactly("a");
        assertThat(dataDirectory.resolve("backups")).doesNotExist();
    }

    @Test
    void aBackupCannotBeWrittenOverTheDatabase() {
        addPlayers("a");

        assertThat(run("backup", "--output", dataDirectory.resolve("database/modl.db").toString())).isEqualTo(2);
        assertThat(playerIds()).containsExactly("a");
    }

    @Test
    void reportsUsageErrorsForMissingOptions() {
        assertThat(run("backup")).isEqualTo(2);
        assertThat(run("restore", "--unknown", "value")).isEqualTo(2);
        assertThat(run("backup", "--output", "--with-storage")).isEqualTo(2);
        assertThat(run("restore", "--input", "x.zip", "--with-storage")).isEqualTo(2);
        assertThat(run("copy-database", "--to", "sqlite")).isEqualTo(2);
        assertThat(run("copy-database", "--to", "jdbc:postgresql://user:secret@localhost/modl")).isEqualTo(2);
        assertThat(run("copy-database", "--to", "jdbc:postgresql://localhost/modl", "--password-env", "MODL_TEST_UNSET_VARIABLE"))
            .isEqualTo(2);
    }

    @Test
    void backupWithStorageFollowsTheStorageChosenInThePanel() throws IOException {
        addPlayers("a");
        storeSettings("storageBackend", "{\"active\":\"S3\"}");

        assertThat(run("backup", "--output", archive().toString(), "--with-storage")).isEqualTo(1);
        assertThat(archive()).doesNotExist();
    }

    @Test
    void backupWithStorageWaitsForARunningStorageMove() throws IOException {
        addPlayers("a");
        storeSettings("storageBackend", "{\"active\":\"LOCAL\",\"migratingTo\":\"S3\"}");

        assertThat(run("backup", "--output", archive().toString(), "--with-storage")).isEqualTo(1);
    }

    @Test
    void theStorageVariableOverridesThePanelChoiceForBackups() throws IOException {
        addPlayers("a");
        storeSettings("storageBackend", "{\"active\":\"S3\"}");
        environment = with("MODL_STORAGE", "local");

        assertThat(run("backup", "--output", archive().toString(), "--with-storage")).isZero();
    }

    @Test
    void backupWithStorageRestoresTheDatabaseAndTheUploadedFilesTogether() throws IOException {
        addPlayers("a");
        Path storage = dataDirectory.resolve("storage");
        write(storage.resolve("instance/tickets/evidence.png"), "evidence");
        write(storage.resolve(".meta/instance/tickets/evidence.png"), "metadata");
        Path archive = archive();

        assertThat(run("backup", "--output", archive.toString(), "--with-storage")).isZero();
        Files.delete(storage.resolve("instance/tickets/evidence.png"));
        write(storage.resolve("instance/tickets/added-later.png"), "later");
        addPlayers("b");
        assertThat(run("restore", "--input", archive.toString())).isZero();

        assertThat(storageFiles(storage)).containsExactly(".meta/instance/tickets/evidence.png", "instance/tickets/evidence.png");
        assertThat(Files.readString(storage.resolve("instance/tickets/evidence.png"))).isEqualTo("evidence");
        assertThat(playerIds()).containsExactly("a");
        try (ZipFile safetyBackup = new ZipFile(dataDirectory.resolve("backups").resolve(backupNames().getFirst()).toFile())) {
            assertThat(safetyBackup.stream().map(ZipEntry::getName)).noneMatch(name -> name.startsWith("storage.files/"));
        }
    }

    @Test
    void anUploadIsServedAgainAfterBackupWithStorageAndRestore() throws IOException {
        addPlayers("a");
        Path storage = dataDirectory.resolve("storage");
        String key = "instance/evidence/Pun-1/Clip.png";
        byte[] upload = "evidence".getBytes(StandardCharsets.UTF_8);
        localStorage(storage).write(key, new ByteArrayInputStream(upload), upload.length, "image/png");
        Path archive = archive();
        assertThat(run("backup", "--output", archive.toString(), "--with-storage")).isZero();
        assertThat(localStorage(storage).deleteAll(List.of(key))).isEmpty();
        assertThat(localStorage(storage).exists(key)).isFalse();

        assertThat(run("restore", "--input", archive.toString())).isZero();

        LocalObjectStorage restored = localStorage(storage);
        assertThat(restored.stat(key)).hasValueSatisfying(stored -> assertThat(stored.contentType()).isEqualTo("image/png"));
        try (InputStream served = restored.read(key)) {
            assertThat(served.readAllBytes()).isEqualTo(upload);
        }
    }

    @Test
    void aStopBetweenTheDatabaseAndTheUploadsIsReportedAtTheNextStartAndRestore() throws Exception {
        addPlayers("a");
        Path storage = dataDirectory.resolve("storage");
        write(storage.resolve("instance/tickets/evidence.png"), "evidence");
        Path archive = archive();
        assertThat(run("backup", "--output", archive.toString(), "--with-storage")).isZero();
        assumeTrue(storage.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Set<PosixFilePermission> writable = Files.getPosixFilePermissions(storage);
        makeReadOnly(storage);
        boolean permissionsAreEnforced = !Files.isWritable(storage);
        Files.setPosixFilePermissions(storage, writable);
        assumeTrue(permissionsAreEnforced, "this user can write to a read-only directory");
        RestoreCommand restore = new RestoreCommand();
        String[] command = {"restore", "--input", archive.toString()};
        CliArguments arguments = CliArguments.parse(command, restore.options(), restore.flags());
        PrintStream blockingUploads = new PrintStream(OutputStream.nullOutputStream()) {
            @Override
            public void println(String line) {
                if (line.startsWith("The safety backup holds")) {
                    makeReadOnly(storage);
                }
            }
        };
        try {
            assertThatThrownBy(() -> restore.run(arguments,
                new CliContext(CliSettings.resolve(environment), blockingUploads, Clock.systemUTC())))
                .hasMessageContaining("The database was restored, but the uploaded files were not replaced");
        } finally {
            Files.setPosixFilePermissions(storage, writable);
        }

        OperatorNotice notice = mock(OperatorNotice.class);
        new InterruptedRestoreNotice(new DataDirectoryProperties(dataDirectory.toString()), notice).afterSingletonsInstantiated();
        verify(notice).publish(eq("{}"), contains(archive.toString()));

        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        restore.run(arguments, new CliContext(CliSettings.resolve(environment),
            new PrintStream(printed, true, StandardCharsets.UTF_8), Clock.systemUTC()));

        assertThat(printed.toString(StandardCharsets.UTF_8)).contains("may not match");
        assertThat(dataDirectory.resolve(UploadsRestoreMarker.FILE_NAME)).doesNotExist();
    }

    @Test
    void aBackupWithoutFilesLeavesTheUploadedFilesAlone() throws IOException {
        addPlayers("a");
        Path archive = archive();
        assertThat(run("backup", "--output", archive.toString())).isZero();
        Path storage = dataDirectory.resolve("storage");
        write(storage.resolve("instance/tickets/kept.png"), "kept");

        assertThat(run("restore", "--input", archive.toString())).isZero();

        assertThat(storageFiles(storage)).containsExactly("instance/tickets/kept.png");
    }

    @Test
    void aDatabaseOnlyRestoreOfAStorageBackupKeepsTheCurrentUploads() throws IOException {
        addPlayers("a");
        Path storage = dataDirectory.resolve("storage");
        write(storage.resolve("instance/tickets/backed-up.png"), "old");
        Path archive = archive();
        assertThat(run("backup", "--output", archive.toString(), "--with-storage")).isZero();
        Files.delete(storage.resolve("instance/tickets/backed-up.png"));
        write(storage.resolve("instance/tickets/current.png"), "current");
        addPlayers("b");

        assertThat(ModlCli.run(new String[] {"restore", "--input", archive.toString()}, with("modl.storage.type", "s3"))).isEqualTo(1);
        assertThat(playerIds()).containsExactly("a", "b");
        assertThat(run("restore", "--input", archive.toString(), "--database-only")).isZero();

        assertThat(playerIds()).containsExactly("a");
        assertThat(storageFiles(storage)).containsExactly("instance/tickets/current.png");
    }

    @Test
    void onlyTheNewestThreeSafetyBackupsAreKept() throws IOException {
        addPlayers("a");
        Path archive = archive();
        assertThat(run("backup", "--output", archive.toString())).isZero();
        Path backups = Files.createDirectories(dataDirectory.resolve("backups"));
        for (String day : List.of("20200101", "20200102", "20200103", "20200104")) {
            Files.writeString(backups.resolve("pre-restore-" + day + "-040000.zip"), "older");
        }
        Files.writeString(backups.resolve("modl-backup-20200101-040000.zip"), "other kind");

        assertThat(run("restore", "--input", archive.toString())).isZero();

        List<String> names = backupNames();
        assertThat(names).hasSize(4).contains("modl-backup-20200101-040000.zip", "pre-restore-20200103-040000.zip",
            "pre-restore-20200104-040000.zip");
    }

    @Test
    void storageBackupsAreRefusedWhenUploadsLiveInS3() {
        Path archive = archive();

        assertThat(ModlCli.run(new String[] {"backup", "--output", archive.toString(), "--with-storage"},
            with("modl.storage.type", "s3"))).isEqualTo(1);
        assertThat(archive).doesNotExist();
    }

    @Test
    void aStorageBackupCannotBeWrittenIntoTheStorageFolder() {
        Path archive = dataDirectory.resolve("storage/backup.zip");

        assertThat(run("backup", "--output", archive.toString(), "--with-storage")).isEqualTo(2);
        assertThat(archive).doesNotExist();
    }

    @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
    @MethodSource("gg.modl.backend.support.sql.TestDialects#external")
    void copyDatabaseMovesEveryRowToAnExternalDatabaseAndBack(DialectTarget target) throws Exception {
        Map<String, List<String>> saved;
        try (SqlTestDatabase database = startDatabase()) {
            BackupTestData.seed(database);
            saved = BackupTestData.contents(database);
        }
        SqlDatabaseProperties external = TestDialects.freshDatabase(target);
        createTableOfAnotherPlugin(external);
        String url = external.externalUrl().orElseThrow();
        Map<String, Object> copying = with(PASSWORD_VARIABLE, external.password());
        String[] copy = {"copy-database", "--to", url, "--username", external.username(), "--password-env", PASSWORD_VARIABLE};

        assertThat(ModlCli.run(copy, copying)).isZero();
        assertThat(rowsOfAnotherPlugin(external)).containsExactly("untouched");

        try (SqlTestDatabase copied = SqlTestDatabase.start(target, dataDirectory, external, WriteAttemptHook.NONE)) {
            assertThat(BackupTestData.contents(copied)).isEqualTo(saved);
        }
        assertThat(ModlCli.run(copy, copying)).as("a second copy into a database with modl tables").isEqualTo(1);
        assertThat(rowsOfAnotherPlugin(external)).containsExactly("untouched");

        Path otherDirectory = dataDirectory.resolve("back");
        Map<String, Object> back = Map.of("modl.data-dir", otherDirectory.toString(), "modl.database.url", url,
            "modl.database.username", external.username(), "modl.database.password", external.password());
        assertThat(ModlCli.run(new String[] {"copy-database", "--to", "sqlite"}, back)).isZero();

        try (SqlTestDatabase returned = SqlTestDatabase.start(TestDialects.SQLITE, otherDirectory)) {
            assertThat(BackupTestData.contents(returned)).isEqualTo(saved);
        }
    }

    @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
    @MethodSource("gg.modl.backend.support.sql.TestDialects#external")
    void copyDatabaseIsRefusedWhileTheTargetLockIsHeld(DialectTarget target) {
        addPlayers("a");
        SqlDatabaseProperties external = TestDialects.freshDatabase(target);
        try (SqlTestDatabase running = SqlTestDatabase.start(target, dataDirectory.resolve("other"), external, WriteAttemptHook.NONE)) {
            int exitCode = ModlCli.run(new String[] {"copy-database", "--to", external.externalUrl().orElseThrow(),
                "--username", external.username(), "--password-env", PASSWORD_VARIABLE}, with(PASSWORD_VARIABLE, external.password()));

            assertThat(exitCode).isEqualTo(1);
            assertThat(running.dsl().fetchCount(PLAYERS)).isZero();
        }
    }

    private static void createTableOfAnotherPlugin(SqlDatabaseProperties external) throws Exception {
        try (Connection connection = connect(external); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE otherplugin_data (contents VARCHAR(32))");
            statement.execute("INSERT INTO otherplugin_data VALUES ('untouched')");
        }
    }

    private static List<String> rowsOfAnotherPlugin(SqlDatabaseProperties external) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection connection = connect(external); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT contents FROM otherplugin_data")) {
            while (result.next()) {
                rows.add(result.getString(1));
            }
        }
        return rows;
    }

    private static Connection connect(SqlDatabaseProperties external) throws Exception {
        Driver driver = Class.forName(external.dialect().driverClassName()).asSubclass(Driver.class).getConstructor().newInstance();
        Properties credentials = new Properties();
        credentials.setProperty("user", external.username());
        credentials.setProperty("password", external.password());
        return driver.connect(external.externalUrl().orElseThrow(), credentials);
    }

    private int run(String... arguments) {
        return ModlCli.run(arguments, environment);
    }

    private Map<String, Object> with(String name, Object value) {
        Map<String, Object> changed = new HashMap<>(environment);
        changed.put(name, value);
        return changed;
    }

    private Path archive() {
        return dataDirectory.resolve("exports/backup.zip");
    }

    private SqlTestDatabase startDatabase() {
        return SqlTestDatabase.start(TestDialects.SQLITE, dataDirectory);
    }

    private void addPlayers(String... ids) {
        try (SqlTestDatabase database = startDatabase()) {
            for (String id : ids) {
                addPlayer(database, id);
            }
        }
    }

    private static void addPlayer(SqlTestDatabase database, String id) {
        database.unitOfWork().write(() -> {
            database.dsl().insertInto(PLAYERS).set(BackupTestData.player(id, false)).execute();
        });
    }

    private void storeSettings(String type, String json) {
        try (SqlTestDatabase database = startDatabase()) {
            database.unitOfWork().write(() -> {
                database.dsl().insertInto(SETTINGS)
                    .set(SETTINGS.TYPE, type)
                    .set(SETTINGS.DATA_JSON, json)
                    .set(SETTINGS.VERSION, 1L)
                    .set(SETTINGS.UPDATED_AT, Instant.EPOCH)
                    .execute();
            });
        }
    }

    private List<String> playerIds() {
        try (SqlTestDatabase database = startDatabase()) {
            return database.dsl().select(PLAYERS.ID).from(PLAYERS).orderBy(PLAYERS.ID).fetch(PLAYERS.ID);
        }
    }

    private List<String> backupNames() throws IOException {
        try (Stream<Path> files = Files.list(dataDirectory.resolve("backups"))) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static LocalObjectStorage localStorage(Path root) {
        return new LocalObjectStorage(root, new UploadSigner(() -> "signing-key"), Clock.systemUTC());
    }

    private static void makeReadOnly(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-xr-xr-x"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static List<String> storageFiles(Path storage) throws IOException {
        try (Stream<Path> walk = Files.walk(storage)) {
            return walk.filter(Files::isRegularFile)
                .map(path -> storage.relativize(path).toString().replace('\\', '/'))
                .sorted()
                .toList();
        }
    }
}
