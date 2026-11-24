package gg.modl.backend.database.sql;

import static gg.modl.backend.database.jooq.Tables.INSTANCE_SECRETS;
import static gg.modl.backend.database.jooq.Tables.PANEL_SESSIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.backup.BackupDiskSpace;
import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.WriteAttemptHook;
import gg.modl.backend.database.backup.ArchivingDatabaseBackup;
import gg.modl.backend.database.backup.BackupTestData;
import gg.modl.backend.database.backup.DatabaseCopy;
import gg.modl.backend.database.backup.TransferReport;
import gg.modl.backend.database.sql.DatabaseTargetException.Problem;
import gg.modl.backend.database.sql.TargetInspection.Contents;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CopyTargetContractTest {
    private static final String KEY = "storage-signing-key-of-this-installation";

    @TempDir
    Path directory;

    @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
    @MethodSource("gg.modl.backend.support.sql.TestDialects#external")
    void anEmptyDatabaseIsReadyAndTheProbeLeavesNothingBehind(DialectTarget target) throws Exception {
        SqlDatabaseProperties external = TestDialects.freshDatabase(target);

        try (CopyTarget opened = CopyTarget.open(external, dataDirectory("checked"))) {
            TargetInspection inspection = opened.inspect(KEY);

            assertThat(inspection.contents()).isEqualTo(Contents.EMPTY);
            assertThat(inspection.unsupportedVersion()).isEmpty();
            assertThat(inspection.missingPrivileges()).isEmpty();
            assertThat(inspection.serverVersion()).isNotBlank();
        }
        assertThat(tables(external)).isEmpty();
    }

    @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
    @MethodSource("gg.modl.backend.support.sql.TestDialects#external")
    void tablesLeftByAnInterruptedProbeAreRemovedByTheNextOne(DialectTarget target) throws Exception {
        SqlDatabaseProperties external = TestDialects.freshDatabase(target);
        try (Connection connection = ConnectionCheck.connect(external); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE modl_probe_parent_0a1b2c3d (id INTEGER NOT NULL PRIMARY KEY)");
            statement.execute("CREATE TABLE modl_probe_child_0a1b2c3d (id INTEGER NOT NULL PRIMARY KEY, parent_id INTEGER NOT NULL,"
                + " CONSTRAINT modl_fk_probe_0a1b2c3d FOREIGN KEY (parent_id) REFERENCES modl_probe_parent_0a1b2c3d (id))");
        }

        try (CopyTarget opened = CopyTarget.open(external, dataDirectory("leftovers"))) {
            assertThat(opened.inspect(KEY).contents()).isEqualTo(Contents.EMPTY);
        }
        assertThat(tables(external)).isEmpty();
    }

    @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
    @MethodSource("gg.modl.backend.support.sql.TestDialects#external")
    void aCopyHoldsEveryRowAndIsRecognisedAsThisInstallation(DialectTarget target) throws Exception {
        Map<String, List<String>> saved = seedBuiltIn();
        SqlDatabaseProperties external = TestDialects.freshDatabase(target);

        TransferReport report = copyBuiltInInto(external);

        try (CopyTarget reopened = CopyTarget.open(external, dataDirectory("checked"))) {
            assertThat(reopened.countRowsByTable()).isEqualTo(report.rowsByTable());
            assertThat(report.mismatchesWith(reopened.countRowsByTable())).isEmpty();
            assertThat(reopened.inspect(KEY).contents()).isEqualTo(Contents.COPY_OF_THIS_INSTALLATION);
            assertThat(reopened.inspect("another installation").contents()).isEqualTo(Contents.OTHER_DATA);
        }
        assertThat(copyBuiltInInto(external).rows()).as("an older copy is replaced").isEqualTo(report.rows());
        try (SqlTestDatabase copied = SqlTestDatabase.start(target, dataDirectory("copied").root(), external, WriteAttemptHook.NONE)) {
            assertThat(BackupTestData.contents(copied)).isEqualTo(saved);
        }
    }

    @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
    @MethodSource("gg.modl.backend.support.sql.TestDialects#external")
    void theBuiltInDatabaseIsATargetForMovingBack(DialectTarget target) throws Exception {
        Map<String, List<String>> saved = seedBuiltIn();
        SqlDatabaseProperties external = TestDialects.freshDatabase(target);
        copyBuiltInInto(external);
        DataDirectoryProperties back = dataDirectory("back");

        try (OfflineDatabase source = OfflineDatabase.open(external, back);
             CopyTarget builtIn = CopyTarget.open(SqlDatabaseProperties.builtIn(), back)) {
            DatabaseCopy copy = new DatabaseCopy(new ArchivingDatabaseBackup(source.source(), back.temporary(), BackupDiskSpace.SYSTEM,
                Clock.systemUTC()), back.temporary(), BackupDiskSpace.SYSTEM, Clock.systemUTC());
            assertThat(builtIn.inspect(KEY).contents()).isEqualTo(Contents.EMPTY);
            copy.into(builtIn.target());
            assertThat(builtIn.inspect(KEY).contents()).isEqualTo(Contents.COPY_OF_THIS_INSTALLATION);
            assertThat(copy.into(builtIn.target()).rowsByTable()).as("an older built-in copy is replaced")
                .isEqualTo(builtIn.countRowsByTable());
        }
        try (SqlTestDatabase returned = SqlTestDatabase.start(TestDialects.SQLITE, back.root())) {
            assertThat(BackupTestData.contents(returned)).isEqualTo(saved);
        }
    }

    @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
    @MethodSource("gg.modl.backend.support.sql.TestDialects#external")
    void aDatabaseAnotherServerUsesIsInUse(DialectTarget target) {
        SqlDatabaseProperties external = TestDialects.freshDatabase(target);
        try (SqlTestDatabase running = SqlTestDatabase.start(target, dataDirectory("running").root(), external, WriteAttemptHook.NONE)) {
            assertThatThrownBy(() -> CopyTarget.open(external, dataDirectory("checked")))
                .isInstanceOfSatisfying(DatabaseTargetException.class, refused -> assertThat(refused.problem()).isEqualTo(Problem.IN_USE));
        }
    }

    @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
    @MethodSource("gg.modl.backend.support.sql.TestDialects#external")
    void aWrongPasswordIsASignInFailure(DialectTarget target) {
        SqlDatabaseProperties external = TestDialects.freshDatabase(target);
        SqlDatabaseProperties wrong = new SqlDatabaseProperties(external.externalUrl().orElseThrow(), external.username(), "wrong",
            null, null);

        assertThatThrownBy(() -> CopyTarget.open(wrong, dataDirectory("checked")))
            .isInstanceOfSatisfying(DatabaseTargetException.class,
                refused -> assertThat(refused.problem()).isEqualTo(Problem.SIGN_IN_FAILED));
    }

    @Test
    void aServerThatDoesNotAnswerIsUnreachable() {
        SqlDatabaseProperties closed = new SqlDatabaseProperties("jdbc:postgresql://127.0.0.1:1/modl", "modl", "secret", null, null);

        assertThatThrownBy(() -> CopyTarget.open(closed, dataDirectory("checked")))
            .isInstanceOfSatisfying(DatabaseTargetException.class,
                refused -> assertThat(refused.problem()).isEqualTo(Problem.UNREACHABLE));
        assertThat(ConnectionCheck.failureToConnect(closed)).isPresent();
    }

    private Map<String, List<String>> seedBuiltIn() {
        try (SqlTestDatabase database = SqlTestDatabase.start(TestDialects.SQLITE, directory)) {
            BackupTestData.seed(database);
            database.unitOfWork().write(() -> {
                database.dsl().insertInto(INSTANCE_SECRETS)
                    .set(INSTANCE_SECRETS.ID, 1)
                    .set(INSTANCE_SECRETS.AUTH_CODE_HASH_SECRET, "auth-secret")
                    .set(INSTANCE_SECRETS.STORAGE_SIGNING_KEY, KEY)
                    .set(INSTANCE_SECRETS.CREATED_AT, SqlTestDatabase.START)
                    .execute();
                database.dsl().insertInto(PANEL_SESSIONS)
                    .set(PANEL_SESSIONS.ID_HASH, "a".repeat(64))
                    .set(PANEL_SESSIONS.EMAIL, "owner@example.com")
                    .set(PANEL_SESSIONS.SESSION_KEY_HASH, "key-hash")
                    .set(PANEL_SESSIONS.CREATED_AT, SqlTestDatabase.START)
                    .set(PANEL_SESSIONS.EXPIRES_AT, SqlTestDatabase.START.plusSeconds(86_400))
                    .execute();
            });
            return BackupTestData.contents(database);
        }
    }

    private TransferReport copyBuiltInInto(SqlDatabaseProperties external) throws Exception {
        DataDirectoryProperties own = new DataDirectoryProperties(directory.toString());
        try (OfflineDatabase source = OfflineDatabase.open(SqlDatabaseProperties.builtIn(), own);
             CopyTarget opened = CopyTarget.open(external, own)) {
            return new DatabaseCopy(new ArchivingDatabaseBackup(source.source(), own.temporary(), BackupDiskSpace.SYSTEM,
                Clock.systemUTC()), own.temporary(), BackupDiskSpace.SYSTEM, Clock.systemUTC()).into(opened.target());
        }
    }

    private DataDirectoryProperties dataDirectory(String name) {
        return new DataDirectoryProperties(directory.resolve(name).toString());
    }

    private static List<String> tables(SqlDatabaseProperties external) throws Exception {
        List<String> tables = new ArrayList<>();
        try (Connection connection = ConnectionCheck.connect(external);
             ResultSet result = connection.getMetaData().getTables(connection.getCatalog(), connection.getSchema(), "%",
                 new String[] {"TABLE"})) {
            while (result.next()) {
                tables.add(result.getString("TABLE_NAME"));
            }
        }
        return tables;
    }
}
