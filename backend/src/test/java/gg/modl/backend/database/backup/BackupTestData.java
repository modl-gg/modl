package gg.modl.backend.database.backup;

import static gg.modl.backend.database.jooq.Tables.AUDIT_LOGS;
import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_LINKS;
import static gg.modl.backend.database.jooq.Tables.SETTINGS;
import static gg.modl.backend.database.jooq.Tables.WEBAUTHN_CREDENTIALS;

import gg.modl.backend.database.jooq.DefaultSchema;
import gg.modl.backend.database.jooq.tables.records.AuditLogsRecord;
import gg.modl.backend.database.jooq.tables.records.PlayerLinksRecord;
import gg.modl.backend.database.jooq.tables.records.PlayersRecord;
import gg.modl.backend.database.jooq.tables.records.SettingsRecord;
import gg.modl.backend.database.jooq.tables.records.WebauthnCredentialsRecord;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.jooq.Table;

public final class BackupTestData {
    public static final int PLAYER_COUNT = 1_203;

    private static final long LOG_ID = 1_795_000_000_123_456_789L;
    private static final byte[] PUBLIC_KEY = {0, 1, 2, -1, -128, 127, 10, 13};
    private static final Instant NOW = SqlTestDatabase.START;

    private BackupTestData() {
    }

    public static void seed(SqlTestDatabase database) {
        database.unitOfWork().write(() -> {
            for (int index = 0; index < PLAYER_COUNT; index++) {
                database.dsl().insertInto(PLAYERS).set(player("player-" + index, index % 2 == 0)).execute();
            }
            database.dsl().insertInto(PLAYER_LINKS).set(new PlayerLinksRecord("player-0", "player-1", NOW, false)).execute();
            database.dsl().insertInto(SETTINGS).set(new SettingsRecord("general",
                "{\"name\":\"café 🚀\\nline\",\"$key.with.dots\":[1,2]}", 7L, NOW)).execute();
            database.dsl().insertInto(WEBAUTHN_CREDENTIALS).set(new WebauthnCredentialsRecord("credential", "owner@example.com",
                "credential-id", PUBLIC_KEY, 4_294_967_296L, null, null, NOW, null)).execute();
            database.dsl().insertInto(AUDIT_LOGS).set(new AuditLogsRecord(LOG_ID, "moderation", "test", "line one\nline two",
                null, NOW)).execute();
        });
    }

    public static PlayersRecord player(String id, boolean online) {
        return new PlayersRecord(id, UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString(), id, id, online, null, NOW, null, null, 5L,
            null, null, null, NOW);
    }

    public static Map<String, List<String>> contents(SqlTestDatabase database) {
        Map<String, List<String>> contents = new TreeMap<>();
        for (Table<?> table : DefaultSchema.DEFAULT_SCHEMA.getTables()) {
            contents.put(table.getName(), database.dsl().selectFrom(table).fetch().stream()
                .map(row -> Arrays.deepToString(row.intoArray()))
                .sorted()
                .toList());
        }
        return contents;
    }
}
