package gg.modl.backend.database;

import static gg.modl.backend.database.jooq.Tables.AUDIT_LOGS;
import static gg.modl.backend.database.jooq.Tables.CHAT_LOGS;
import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.SETTINGS;
import static gg.modl.backend.database.jooq.Tables.STAFF_ROLES;
import static gg.modl.backend.database.jooq.Tables.WEBAUTHN_CREDENTIALS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.database.jooq.tables.records.AuditLogsRecord;
import gg.modl.backend.database.jooq.tables.records.ChatLogsRecord;
import gg.modl.backend.database.jooq.tables.records.PlayersRecord;
import gg.modl.backend.database.jooq.tables.records.SettingsRecord;
import gg.modl.backend.database.jooq.tables.records.StaffRolesRecord;
import gg.modl.backend.database.jooq.tables.records.WebauthnCredentialsRecord;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.io.TempDir;

class BindingRoundTripContractTest {
    private static final Instant LATEST_INSTANT = Instant.parse("9999-12-31T23:59:59.999Z");
    private static final long SEQUENCER_SCALE_FLOOR = 1_800_000_000_000_000_000L;
    private static final int BATCH_ROWS = 1_000;
    private static final String SUPPLEMENTARY_TEXT = "gg 😀 𝔘nicode üß 中文 🏳️‍🌈";

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void booleansRoundTripBothWays(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            KernelRows.insert(database,
                new StaffRolesRecord("yes", "Yes", "yes", null, true, 1, KernelRows.NOW, KernelRows.NOW),
                new StaffRolesRecord("no", "No", "no", null, false, 2, KernelRows.NOW, KernelRows.NOW));

            assertTrue(database.dsl().fetchSingle(STAFF_ROLES, STAFF_ROLES.ID.eq("yes")).getIsDefault());
            assertFalse(database.dsl().fetchSingle(STAFF_ROLES, STAFF_ROLES.ID.eq("no")).getIsDefault());
            assertEquals(1, database.dsl().fetchCount(STAFF_ROLES, STAFF_ROLES.IS_DEFAULT.isTrue()));
        }
    }

    @SqlContractTest
    void instantsRoundTripAtTheEpochAndAtTheLastRepresentableMillisecond(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayersRecord player = KernelRows.player("p1");
            player.setFirstJoinAt(Instant.EPOCH);
            player.setLastLoginAt(LATEST_INSTANT);
            player.setLastLogoutAt(Instant.parse("2026-03-04T05:06:07.089Z"));
            KernelRows.insert(database, player);

            PlayersRecord stored = database.dsl().fetchSingle(PLAYERS, PLAYERS.ID.eq("p1"));

            assertEquals(Instant.EPOCH, stored.getFirstJoinAt());
            assertEquals(LATEST_INSTANT, stored.getLastLoginAt());
            assertEquals(Instant.parse("2026-03-04T05:06:07.089Z"), stored.getLastLogoutAt());
            assertEquals(1, database.dsl().fetchCount(PLAYERS, PLAYERS.LAST_LOGIN_AT.ge(LATEST_INSTANT)));
        }
    }

    @SqlContractTest
    void binaryKeysRoundTripEveryByteValue(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            byte[] everyByte = new byte[256];
            IntStream.range(0, everyByte.length).forEach(value -> everyByte[value] = (byte) value);
            KernelRows.insert(database, new WebauthnCredentialsRecord("w1", "a@example.com", "credential-1", everyByte, 0L, null, null,
                KernelRows.NOW, null));

            byte[] stored = database.dsl().fetchSingle(WEBAUTHN_CREDENTIALS, WEBAUTHN_CREDENTIALS.ID.eq("w1")).getPublicKeyCose();

            assertArrayEquals(everyByte, stored);
        }
    }

    @SqlContractTest
    void sequencerLogIdsRoundTripExactlyOnChatAndAuditLogs(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            long chatId = database.sequencer().next();
            long auditId = database.sequencer().next();
            KernelRows.insert(database,
                new ChatLogsRecord(chatId, "p1", "Steve", "hello", "lobby", KernelRows.NOW),
                new AuditLogsRecord(auditId, "info", "system", "started", null, true, KernelRows.NOW));

            assertTrue(chatId > SEQUENCER_SCALE_FLOOR);
            assertEquals(chatId, database.dsl().select(CHAT_LOGS.ID).from(CHAT_LOGS).fetchSingle(CHAT_LOGS.ID));
            assertEquals(auditId, database.dsl().select(AUDIT_LOGS.ID).from(AUDIT_LOGS).fetchSingle(AUDIT_LOGS.ID));
            assertEquals(1, database.dsl().fetchCount(AUDIT_LOGS, AUDIT_LOGS.ID.eq(auditId)));
        }
    }

    @SqlContractTest
    void aBatchInsertOfAThousandRowsStoresEveryRowWithItsExactId(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            List<ChatLogsRecord> rows = new ArrayList<>();
            for (int line = 0; line < BATCH_ROWS; line++) {
                rows.add(new ChatLogsRecord(database.sequencer().next(), "p1", "Steve", "line " + line, "lobby", KernelRows.NOW));
            }

            database.unitOfWork().write(() -> database.dsl().batchInsert(rows).execute());

            List<Long> expected = rows.stream().map(ChatLogsRecord::getId).toList();
            assertEquals(expected, database.dsl().select(CHAT_LOGS.ID).from(CHAT_LOGS).orderBy(CHAT_LOGS.ID).fetch(CHAT_LOGS.ID));
        }
    }

    @SqlContractTest
    void textWithSupplementaryCharactersRoundTrips(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            KernelRows.insert(database, new ChatLogsRecord(1L, "p1", "😀name", SUPPLEMENTARY_TEXT, "lobby", KernelRows.NOW));

            ChatLogsRecord stored = database.dsl().fetchSingle(CHAT_LOGS, CHAT_LOGS.ID.eq(1L));

            assertEquals(SUPPLEMENTARY_TEXT, stored.getMessage());
            assertEquals("😀name", stored.getUsername());
        }
    }

    @SqlContractTest
    void jsonColumnsRoundTripNestedValuesUnicodeAndReservedKeyCharacters(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Map<String, Object> document = nestedDocument();
            KernelRows.insert(database, new SettingsRecord("general", database.jsonColumns().write(document), 0L, KernelRows.NOW));

            String stored = database.dsl().fetchSingle(SETTINGS, SETTINGS.TYPE.eq("general")).getDataJson();

            assertEquals(document, database.jsonColumns().readMap(stored));
        }
    }

    private static Map<String, Object> nestedDocument() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("a.b", "dotted");
        inner.put("$set", "dollar");
        inner.put("list", List.of(1, "two", true, List.of(3.5, Map.of("deep", "😀"))));
        inner.put("missing", null);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("name", "über 中文 𝔘");
        document.put("count", 42);
        document.put("big", Long.MAX_VALUE);
        document.put("nested", inner);
        document.put("$where.key", Map.of("x.y", List.of()));
        return document;
    }
}
