package gg.modl.backend.database.sql;

import static gg.modl.backend.database.jooq.Tables.PANEL_SESSIONS;
import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.database.jooq.Tables.STAFF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import gg.modl.backend.auth.repository.AuthRepositories;
import gg.modl.backend.auth.repository.PanelSession;
import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.WriteAttemptHook;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;

class PasswordSchemaUpgradeContractTest {
    private static final String BEFORE_PASSWORDS = "1";
    private static final String PASSWORDS = "2";
    private static final String INSTANCE_STATE = "4";
    private static final long CREATED = SqlTestDatabase.START.toEpochMilli();
    private static final long EXPIRES = SqlTestDatabase.START.plusSeconds(86_400).toEpochMilli();
    private static final String EMAIL = "mod@example.com";
    private static final List<String> BASELINE_ROWS = List.of(
        "SELECT id_hash, email, session_key_hash, ip_address, user_agent, created_at, expires_at FROM modl_panel_sessions"
            + " ORDER BY id_hash",
        "SELECT id, email, username, created_at, updated_at FROM modl_staff ORDER BY id",
        "SELECT id, minecraft_uuid, username, created_at FROM modl_players ORDER BY id",
        "SELECT id, player_id, type_ordinal, issued_at, enforcement_category FROM modl_punishments ORDER BY id",
        "SELECT player_id, ip, first_seen_at, last_seen_at FROM modl_player_ips ORDER BY player_id",
        "SELECT type, data_json, version FROM modl_settings ORDER BY type");
    private static final List<String> CREDENTIAL_TABLES =
        List.of("modl_staff_credentials", "modl_staff_totp", "modl_staff_recovery_codes", "modl_password_reset_tokens");

    @TempDir
    Path directory;

    private SqlDatabaseProperties database;
    private DataDirectoryProperties dataDirectory;
    private MigrationSteps steps;

    @SqlContractTest
    void versionTwoKeepsTheRowsOfVersionOneAndGivesOldSessionsTheDefaults(DialectTarget target) throws Exception {
        use(target);
        steps.migrateTo(BEFORE_PASSWORDS);
        seedBaseline();
        List<List<String>> before = baselineRows();

        assertThat(steps.migrateTo(PASSWORDS)).isEqualTo(PASSWORDS);

        assertThat(baselineRows()).isEqualTo(before);
        assertThat(rows("SELECT auth_method FROM modl_panel_sessions")).containsExactly("EMAIL_CODE", "EMAIL_CODE");
        assertThat(rows("SELECT count(*) FROM modl_panel_sessions WHERE second_factor_verified = FALSE")).containsExactly("2");
        for (String table : CREDENTIAL_TABLES) {
            assertThat(rows("SELECT count(*) FROM " + table)).as(table).containsExactly("0");
        }
    }

    @SqlContractTest
    void credentialsWrittenOnVersionTwoSurviveVersionFourAndTheLatestSchema(DialectTarget target) throws Exception {
        use(target);
        steps.migrateTo(BEFORE_PASSWORDS);
        seedBaseline();
        List<List<String>> before = baselineRows();
        steps.migrateTo(PASSWORDS);
        seedCredentials();

        assertThat(steps.migrateTo(INSTANCE_STATE)).isEqualTo(INSTANCE_STATE);
        assertThat(baselineRows()).isEqualTo(before);

        try (SqlTestDatabase upgraded = SqlTestDatabase.start(target, dataDirectory.root(), database, WriteAttemptHook.NONE)) {
            assertThat(baselineRows()).isEqualTo(before);
            AuthRepositories repositories = AuthRepositories.on(upgraded);
            Instant now = SqlTestDatabase.START;
            assertThat(repositories.passwords().findPasswordHash(EMAIL)).contains("password-hash");
            assertThat(repositories.totp().find(EMAIL)).get().satisfies(totp -> {
                assertThat(totp.secret()).isEqualTo("TOTPSECRET");
                assertThat(totp.lastUsedStep()).isEqualTo(58_800_000L);
            });
            assertThat(repositories.totp().countRecoveryCodes(EMAIL)).isEqualTo(1);
            assertThat(repositories.resetTokens().findActive("token-hash", now)).isPresent();
            assertThat(repositories.sessions().findByEmail(EMAIL, now))
                .extracting(PanelSession::authMethod, PanelSession::secondFactorVerified)
                .containsOnly(tuple(AuthMethod.EMAIL_CODE, false));
            assertThat(upgraded.dsl().fetchCount(PANEL_SESSIONS)).isEqualTo(2);
            assertThat(upgraded.dsl().fetchCount(STAFF)).isEqualTo(1);
            assertThat(upgraded.dsl().fetchCount(PLAYERS)).isEqualTo(1);
            assertThat(upgraded.dsl().fetchCount(PUNISHMENTS)).isEqualTo(1);
        }
    }

    private void use(DialectTarget target) throws Exception {
        database = TestDialects.freshDatabase(target);
        dataDirectory = new DataDirectoryProperties(directory.toString());
        steps = MigrationSteps.on(target, database, dataDirectory);
    }

    private void seedBaseline() throws SQLException {
        String times = CREATED + ", " + EXPIRES;
        execute(
            "INSERT INTO modl_panel_sessions (id_hash, email, session_key_hash, ip_address, created_at, expires_at) "
                + "VALUES ('session-1', '" + EMAIL + "', 'key-1', '127.0.0.1', " + times + ")",
            "INSERT INTO modl_panel_sessions (id_hash, email, session_key_hash, created_at, expires_at) "
                + "VALUES ('session-2', '" + EMAIL + "', 'key-2', " + times + ")",
            "INSERT INTO modl_staff (id, email, username, created_at, updated_at) "
                + "VALUES ('staff-1', '" + EMAIL + "', 'Mod', " + CREATED + ", " + CREATED + ")",
            "INSERT INTO modl_players (id, minecraft_uuid, username, username_lower, created_at) "
                + "VALUES ('player-1', 'uuid-1', 'Player', 'player', " + CREATED + ")",
            "INSERT INTO modl_punishments (id, player_id, type_ordinal, issued_at, enforcement_category, active_until_at) "
                + "VALUES ('punish-1', 'player-1', 2, " + CREATED + ", 'BAN', " + EXPIRES + ")",
            "INSERT INTO modl_player_ips (player_id, ip, first_seen_at, last_seen_at) "
                + "VALUES ('player-1', '10.0.0.1', " + CREATED + ", " + CREATED + ")",
            "INSERT INTO modl_settings (type, data_json, version, updated_at) VALUES ('general', '{\"a\":1}', 3, " + CREATED + ")");
    }

    private void seedCredentials() throws SQLException {
        execute(
            "INSERT INTO modl_staff_credentials (email, password_hash, created_at, updated_at) "
                + "VALUES ('" + EMAIL + "', 'password-hash', " + CREATED + ", " + CREATED + ")",
            "INSERT INTO modl_staff_totp (email, totp_secret, last_used_step, enabled_at, updated_at) "
                + "VALUES ('" + EMAIL + "', 'TOTPSECRET', 58800000, " + CREATED + ", " + CREATED + ")",
            "INSERT INTO modl_staff_recovery_codes (email, code_hash, created_at) VALUES ('" + EMAIL + "', 'code-hash', " + CREATED + ")",
            "INSERT INTO modl_password_reset_tokens (email, token_hash, created_at, expires_at) "
                + "VALUES ('" + EMAIL + "', 'token-hash', " + CREATED + ", " + EXPIRES + ")");
    }

    private List<List<String>> baselineRows() throws SQLException {
        List<List<String>> rows = new ArrayList<>();
        for (String query : BASELINE_ROWS) {
            rows.add(rows(query));
        }
        return rows;
    }

    private void execute(String... statements) throws SQLException {
        try (Connection connection = steps.connections().getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
        }
    }

    private List<String> rows(String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = steps.connections().getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            int columns = result.getMetaData().getColumnCount();
            while (result.next()) {
                List<String> values = new ArrayList<>();
                for (int column = 1; column <= columns; column++) {
                    values.add(result.getString(column));
                }
                rows.add(String.join("|", values));
            }
        }
        return rows;
    }
}
