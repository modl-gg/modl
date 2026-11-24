package gg.modl.minecraft.core.imports.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlSessionSchemaTest {
    @TempDir
    Path root;

    @Test
    void unquotedNamesAreFoundInTheCaseTheDatabaseStoresThem() throws Exception {
        String url = "jdbc:h2:file:" + root.resolve("h2") + ";MODE=MySQL";
        try (SqlSession session = session(JdbcDriver.H2_LEGACY, url, "", "CREATE TABLE bm_players (id INT, lastSeen BIGINT)")) {
            assertTrue(session.hasTable("bm_players"));
            assertTrue(session.hasColumn("bm_players", "lastSeen"));
            assertFalse(session.hasColumn("bm_players", "pausedRemaining"));
            assertFalse(session.hasTable("bm_player_notes"));
        }
    }

    @Test
    void quotedLowerCaseNamesAreFoundExactly() throws Exception {
        String url = "jdbc:hsqldb:file:" + root.resolve("hsqldb") + ";shutdown=true";
        try (SqlSession session = session(JdbcDriver.HSQLDB, url, "SA",
                "CREATE TABLE \"libertybans_punishments\" (\"id\" INT, \"scope\" VARCHAR(32))")) {
            assertTrue(session.hasTable("libertybans_punishments"));
            assertTrue(session.hasColumn("libertybans_punishments", "scope"));
            assertFalse(session.hasColumn("libertybans_punishments", "scope_id"));
        }
    }

    @Test
    void aMissingTableIsToldWithoutAFailedQuery() throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("database.db");
        try (SqlSession session = session(JdbcDriver.SQLITE, url, "", "CREATE TABLE bans (id VARCHAR(16), player VARCHAR(36))")) {
            assertTrue(session.hasTable("bans"));
            assertFalse(session.hasTable("unbans"));
            assertFalse(session.hasColumn("unbans", "reason"));
            assertTrue(session.hasColumn("bans", "player"));
        }
    }

    private SqlSession session(JdbcDriver driver, String url, String user, String schema) throws Exception {
        Connection connection = SourceFixtures.connect(driver, url, user);
        try (Statement statement = connection.createStatement()) {
            statement.execute(schema);
        }
        connection.setAutoCommit(false);
        return SqlSession.owning(connection, () -> { });
    }
}
