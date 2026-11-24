package gg.modl.backend.database.backup;

import static gg.modl.backend.database.jooq.Tables.DATABASE_DEPARTURE;
import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_LINKS;
import static gg.modl.backend.database.jooq.Tables.SETTINGS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.backup.BackupFiles;
import gg.modl.backend.backup.BackupSeries;
import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.jooq.DefaultSchema;
import gg.modl.backend.database.jooq.tables.records.PlayerLinksRecord;
import gg.modl.backend.database.sql.DatabaseDeparture;
import gg.modl.backend.database.sql.OfflineDatabase;
import gg.modl.backend.databasemove.repository.JooqDatabaseDepartureRepository;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jooq.Table;
import org.junit.jupiter.api.io.TempDir;

class DatabaseBackupContractTest {
    @TempDir
    Path directory;

    @SqlContractTest
    void aLogicalArchiveRestoresEveryTableAndValueExactly(DialectTarget target) throws IOException {
        BackupScenario scenario = BackupScenario.fresh(target, directory.resolve("data"));
        Map<String, List<String>> saved = scenario.seeded();
        Path archive = directory.resolve("logical.zip");

        TransferReport written = scenario.write(ArchiveKind.LOGICAL, archive);
        addPlayer(scenario, "added-after-the-backup");
        TransferReport restored = scenario.restore(archive);

        assertThat(scenario.contents()).isEqualTo(saved);
        assertThat(restored).isEqualTo(written);
        assertThat(written.tables()).isEqualTo(DefaultSchema.DEFAULT_SCHEMA.getTables().size());
        assertThat(written.rows()).isEqualTo(saved.values().stream().mapToLong(List::size).sum());
        String manifest = TestArchives.manifest(archive);
        assertThat(manifest).contains("\"kind\":\"LOGICAL\"", newestSchema(), "\"appVersion\":\"");
        for (Table<?> table : DefaultSchema.DEFAULT_SCHEMA.getTables()) {
            assertThat(manifest).contains("\"name\":\"" + table.getName() + "\"");
        }
    }

    @SqlContractTest
    void aSnapshotOfTheBuiltInDatabaseRestoresIntoEveryDialect(DialectTarget target) throws IOException {
        BackupScenario sqlite = BackupScenario.fresh(TestDialects.SQLITE, directory.resolve("source"));
        Map<String, List<String>> saved = sqlite.seeded();
        Path archive = directory.resolve("snapshot.zip");
        sqlite.write(ArchiveKind.SNAPSHOT, archive);
        BackupScenario scenario = BackupScenario.fresh(target, directory.resolve("target"));
        addPlayer(scenario, "replaced");

        scenario.restore(archive);

        assertThat(scenario.contents()).isEqualTo(saved);
        assertThat(TestArchives.manifest(archive)).contains("\"kind\":\"SNAPSHOT\"", newestSchema(), "\"appVersion\":\"");
    }

    @SqlContractTest
    void backupsLeaveOutTheMoveRecordThatOnlyTheMoveCopyCarries(DialectTarget target) throws IOException {
        BackupScenario scenario = BackupScenario.fresh(target, directory.resolve("data"));
        scenario.seeded();
        try (SqlTestDatabase running = scenario.start()) {
            JooqDatabaseDepartureRepository departures = new JooqDatabaseDepartureRepository(running.dsl());
            running.unitOfWork().write(() -> departures.mark(new DatabaseDeparture("move", "MySQL at db:3306/modl",
                SqlTestDatabase.START)));
        }
        List<ArchiveKind> kinds = target.isSqlite() ? List.of(ArchiveKind.SNAPSHOT, ArchiveKind.LOGICAL) : List.of(ArchiveKind.LOGICAL);
        Path copy = directory.resolve("copy.zip");
        scenario.writeMoveCopy(copy);
        for (ArchiveKind kind : kinds) {
            scenario.write(kind, directory.resolve(kind + ".zip"));
        }

        for (ArchiveKind kind : kinds) {
            scenario.restore(directory.resolve(kind + ".zip"));
            assertThat(departures(scenario)).as(kind.toString()).isZero();
        }
        scenario.restore(copy);
        assertThat(departures(scenario)).isOne();
    }

    private static int departures(BackupScenario scenario) {
        try (SqlTestDatabase running = scenario.start()) {
            return running.dsl().fetchCount(DATABASE_DEPARTURE);
        }
    }

    @SqlContractTest
    void aLogicalArchiveIsOneSnapshotEvenWhileAWriterCommits(DialectTarget target) throws IOException {
        BackupScenario scenario = BackupScenario.fresh(target, directory.resolve("data"));
        Map<String, List<String>> saved;
        Path archive = directory.resolve("consistent.zip");
        try (SqlTestDatabase running = scenario.start();
             OfflineDatabase offline = OfflineDatabase.open(scenario.database(), scenario.dataDirectory())) {
            BackupTestData.seed(running);
            saved = BackupTestData.contents(running);
            DatabaseBackup backup = new ArchivingDatabaseBackup(offline.source(), scenario.dataDirectory().temporary(),
                BackupScenario.UNLIMITED, BackupScenario.CLOCK);
            try (OutputStream output = Files.newOutputStream(archive);
                 ZipOutputStream zip = new WritingBetweenTables(output, () -> writeLinkedPlayers(running))) {
                backup.write(ArchiveKind.LOGICAL, zip);
            }
            assertThat(running.dsl().fetchCount(PLAYER_LINKS)).isEqualTo(2);
        }

        scenario.restore(archive);

        assertThat(scenario.contents()).isEqualTo(saved);
    }

    @SqlContractTest
    void anArchiveThatIsNotWholeNeverTouchesTheDatabase(DialectTarget target) throws IOException {
        BackupScenario scenario = BackupScenario.fresh(target, directory.resolve("data"));
        scenario.seeded();
        Path archive = directory.resolve("good.zip");
        scenario.write(ArchiveKind.LOGICAL, archive);
        addPlayer(scenario, "kept");
        Map<String, List<String>> current = scenario.contents();
        byte[] whole = Files.readAllBytes(archive);
        Path truncated = Files.write(directory.resolve("truncated.zip"), Arrays.copyOf(whole, whole.length / 2));
        Path missing = TestArchives.withEntry(archive, directory.resolve("missing.zip"), RowLines.entryName(SETTINGS.getName()),
            bytes -> new byte[0]);
        Path flipped = TestArchives.withFlippedByte(archive, directory.resolve("flipped.zip"), "line two");
        Path shortened = TestArchives.withEntry(archive, directory.resolve("shortened.zip"), RowLines.entryName(PLAYERS.getName()),
            bytes -> firstLine(bytes));
        Path newer = TestArchives.withEntry(archive, directory.resolve("newer.zip"), ArchiveManifest.ENTRY_NAME,
            bytes -> replace(bytes, newestSchema(), "\"schemaVersion\":\"999\""));
        Path otherFormat = TestArchives.withEntry(archive, directory.resolve("format.zip"), ArchiveManifest.ENTRY_NAME,
            bytes -> replace(bytes, "\"format\":1", "\"format\":2"));

        assertThatThrownBy(() -> scenario.restore(truncated)).hasMessageContaining("Not a modl backup");
        assertThatThrownBy(() -> scenario.restore(missing)).hasMessageContaining("settings");
        assertThatThrownBy(() -> scenario.restore(flipped)).hasMessageContaining("checksum");
        assertThatThrownBy(() -> scenario.restore(shortened)).hasMessageContaining("holds 1 rows");
        assertThatThrownBy(() -> scenario.restore(newer)).hasMessageContaining("newer than this modl");
        assertThatThrownBy(() -> scenario.restore(otherFormat)).hasMessageContaining("Unsupported backup format 2");

        assertThat(scenario.contents()).isEqualTo(current);
        try (Stream<Path> files = Files.list(workRoot(scenario))) {
            assertThat(files.map(path -> path.getFileName().toString())).noneMatch(name -> name.startsWith(".modl-restore-"));
        }
    }

    @SqlContractTest
    void tooLongValuesAreReportedByColumnBeforeAnythingIsReplaced(DialectTarget target) throws IOException {
        BackupScenario source = BackupScenario.fresh(TestDialects.SQLITE, directory.resolve("source"));
        String tooLong = "🚀".repeat(101);
        try (SqlTestDatabase running = source.start()) {
            running.unitOfWork().write(() -> {
                running.dsl().insertInto(PLAYERS).set(BackupTestData.player("long", false)).execute();
                running.dsl().update(PLAYERS).set(PLAYERS.USERNAME, tooLong).execute();
            });
        }
        Path archive = directory.resolve("long.zip");
        source.write(ArchiveKind.LOGICAL, archive);
        BackupScenario scenario = BackupScenario.fresh(target, directory.resolve("target"));
        addPlayer(scenario, "kept");
        Map<String, List<String>> current = scenario.contents();

        assertThatThrownBy(() -> scenario.restore(archive))
            .hasMessageContaining(PLAYERS.getName() + ".username is longer than 100 characters (1 values)")
            .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(tooLong));
        assertThat(scenario.contents()).isEqualTo(current);
    }

    @SqlContractTest
    void supplementaryCharactersAreCountedAsOneCharacter(DialectTarget target) throws IOException {
        BackupScenario source = BackupScenario.fresh(TestDialects.SQLITE, directory.resolve("source"));
        try (SqlTestDatabase running = source.start()) {
            running.unitOfWork().write(() -> {
                running.dsl().insertInto(PLAYERS).set(BackupTestData.player("exact", false)).execute();
                running.dsl().update(PLAYERS).set(PLAYERS.USERNAME, "🚀".repeat(100)).execute();
            });
        }
        Path archive = directory.resolve("exact.zip");
        source.write(ArchiveKind.LOGICAL, archive);
        BackupScenario scenario = BackupScenario.fresh(target, directory.resolve("target"));

        scenario.restore(archive);

        assertThat(scenario.contents()).isEqualTo(source.contents());
    }

    @SqlContractTest
    void aNamedSeriesKeepsItsOwnNumberOfBackups(DialectTarget target) throws IOException {
        BackupScenario scenario = BackupScenario.fresh(target, directory.resolve("data"));
        scenario.seeded();
        Path backups = Files.createDirectories(scenario.dataDirectory().backups());
        for (String day : List.of("20200101", "20200102", "20200103")) {
            Files.writeString(backups.resolve("pre-update-" + day + "-040000.zip"), "older");
        }
        Files.writeString(backups.resolve("modl-backup-20200101-040000.zip"), "another series");

        try (OfflineDatabase offline = OfflineDatabase.open(scenario.database(), scenario.dataDirectory())) {
            DatabaseBackup backup = new ArchivingDatabaseBackup(offline.source(), scenario.dataDirectory().temporary(),
                BackupScenario.UNLIMITED, BackupScenario.CLOCK);
            backup.writeInto(new BackupSeries(new BackupFiles(backups, BackupScenario.CLOCK), BackupScenario.UNLIMITED,
                "pre-update-", 2), ArchiveKind.LOGICAL);
        }

        try (Stream<Path> files = Files.list(backups)) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactlyInAnyOrder(
                "modl-backup-20200101-040000.zip", "pre-update-20200103-040000.zip", "pre-update-20261121-040000.zip");
        }
        assertThat(TestArchives.manifest(backups.resolve("pre-update-20261121-040000.zip")))
            .contains(newestSchema(), "\"appVersion\":\"");
    }

    private static Path workRoot(BackupScenario scenario) {
        return scenario.database().dialect() == DatabaseDialect.SQLITE
            ? scenario.dataDirectory().database()
            : scenario.dataDirectory().temporary();
    }

    private static void addPlayer(BackupScenario scenario, String id) {
        try (SqlTestDatabase running = scenario.start()) {
            running.unitOfWork().write(() -> {
                running.dsl().insertInto(PLAYERS).set(BackupTestData.player(id, false)).execute();
            });
        }
    }

    private static void writeLinkedPlayers(SqlTestDatabase running) {
        running.unitOfWork().write(() -> {
            running.dsl().insertInto(PLAYERS).set(BackupTestData.player("late-1", false)).execute();
            running.dsl().insertInto(PLAYERS).set(BackupTestData.player("late-2", false)).execute();
            running.dsl().insertInto(PLAYER_LINKS).set(new PlayerLinksRecord("late-1", "late-2", SqlTestDatabase.START, false)).execute();
        });
    }

    private static byte[] firstLine(byte[] lines) {
        for (int index = 0; index < lines.length; index++) {
            if (lines[index] == '\n') {
                return Arrays.copyOf(lines, index + 1);
            }
        }
        return lines;
    }

    private static byte[] replace(byte[] bytes, String text, String replacement) {
        return new String(bytes, StandardCharsets.UTF_8).replace(text, replacement).getBytes(StandardCharsets.UTF_8);
    }

    private static final class WritingBetweenTables extends ZipOutputStream {
        private final Runnable write;

        private WritingBetweenTables(OutputStream output, Runnable write) {
            super(output);
            this.write = write;
        }

        @Override
        public void putNextEntry(ZipEntry entry) throws IOException {
            if (entry.getName().equals(RowLines.entryName(PLAYER_LINKS.getName()))) {
                write.run();
            }
            super.putNextEntry(entry);
        }
    }

    private static String newestSchema() {
        try (Stream<Path> migrations = Files.list(Path.of("src", "main", "resources", "db", "migration", "sqlite"))) {
            int newest = migrations.map(file -> file.getFileName().toString())
                .mapToInt(name -> Integer.parseInt(name.substring(1, name.indexOf("__"))))
                .max()
                .orElseThrow();
            return "\"schemaVersion\":\"" + newest + "\"";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
