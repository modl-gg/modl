package gg.modl.minecraft.core.imports;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import gg.modl.minecraft.core.imports.litebans.LiteBansSource;
import gg.modl.minecraft.core.imports.source.SourceInventory;
import gg.modl.minecraft.core.imports.source.SourceReader;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiteBansExportTest {
    private static final String FIRST = "11111111-1111-4111-8111-111111111111";
    private static final String SECOND = "22222222-2222-4222-8222-222222222222";
    private static final long NOW = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli();
    private static final long HOUR = TimeUnit.HOURS.toMillis(1);
    private static final long MAX_BYTES = 1024 * 1024;

    @TempDir
    Path directory;

    private final FakeLiteBansDatabase database = new FakeLiteBansDatabase();
    private final ImportSignal signal = new ImportSignal();
    private final ExportProgress progress = new ExportProgress();
    private final RecordingPluginLogger logger = new RecordingPluginLogger();
    private boolean enabled = true;
    private final LiteBansSource source = new LiteBansSource(() -> enabled, () -> database, () -> "No reason", () -> NOW);
    private SourceReader reader;

    @BeforeEach
    void openReader() throws Exception {
        reader = source.open();
    }

    @Test
    void aPunishedPlayerWithoutHistoryIsExported() throws Exception {
        database.punished(FakeLiteBansDatabase.BANS, 7, FIRST, NOW - HOUR, 0);

        JsonObject player = exportOnePlayer();

        assertEquals(FIRST, player.get("minecraftUuid").getAsString());
        JsonObject ban = onlyPunishment(player);
        assertEquals("litebans-ban-7", ban.get("_id").getAsString());
        assertEquals(2, ban.get("typeOrdinal").getAsInt());
        assertEquals(0, ban.get("duration").getAsLong());
    }

    @Test
    void anExpiredPunishmentIsNotAPardon() throws Exception {
        Map<String, Object> row = database.punished(FakeLiteBansDatabase.BANS, 1, FIRST, NOW - 2 * HOUR, NOW - HOUR);
        row.put("ACTIVE", false);
        row.put("REMOVED_BY_NAME", "#expired");

        JsonObject data = onlyPunishment(exportOnePlayer()).getAsJsonObject("data");

        assertFalse(data.has("active"));
        assertFalse(onlyPunishment(exportOnePlayer()).has("modifications"));
    }

    @Test
    void aRemovalKeepsTheRemoversNameAndDateWithoutAUuid() throws Exception {
        Map<String, Object> row = database.punished(FakeLiteBansDatabase.MUTES, 2, FIRST, NOW - 2 * HOUR, 0);
        row.put("ACTIVE", false);
        row.put("REMOVED_BY_NAME", "Console");
        row.put("REMOVED_BY_DATE", new Timestamp(NOW - HOUR));

        JsonObject mute = onlyPunishment(exportOnePlayer());

        assertFalse(mute.getAsJsonObject("data").get("active").getAsBoolean());
        JsonObject pardon = mute.getAsJsonArray("modifications").get(0).getAsJsonObject();
        assertEquals("MANUAL_PARDON", pardon.get("type").getAsString());
        assertEquals("Console", pardon.get("issuerName").getAsString());
        assertEquals(Instant.ofEpochMilli(NOW - HOUR).toString(), pardon.get("date").getAsString());
    }

    @Test
    void anEndBeforeTheStartIsNotPermanent() throws Exception {
        database.punished(FakeLiteBansDatabase.BANS, 3, FIRST, NOW - HOUR, NOW - 2 * HOUR);

        assertEquals(1, onlyPunishment(exportOnePlayer()).get("duration").getAsLong());
    }

    @Test
    void anInactivePermanentBanStaysEnded() throws Exception {
        database.punished(FakeLiteBansDatabase.BANS, 4, FIRST, NOW - HOUR, 0).put("ACTIVE", false);

        JsonObject data = onlyPunishment(exportOnePlayer()).getAsJsonObject("data");

        assertFalse(data.get("active").getAsBoolean());
        assertFalse(onlyPunishment(exportOnePlayer()).has("modifications"));
    }

    @Test
    void skipLeavesOutIpWideAndServerScopedPunishmentsAndCountsThem() throws Exception {
        database.punished(FakeLiteBansDatabase.BANS, 1, FIRST, NOW - HOUR, 0).put("IPBAN", true);
        database.punished(FakeLiteBansDatabase.MUTES, 2, FIRST, NOW - HOUR, 0).put("SERVER_SCOPE", "lobby");
        database.punished(FakeLiteBansDatabase.BANS, 3, FIRST, NOW - HOUR, 0);

        JsonObject exported = export(ScopedPunishmentChoice.SKIP, MAX_BYTES);

        assertEquals("litebans-ban-3", onlyPunishment(firstPlayer(exported)).get("_id").getAsString());
        assertEquals(2, progress.skipped());
    }

    @Test
    void widenKeepsIpWideAndServerScopedPunishments() throws Exception {
        database.punished(FakeLiteBansDatabase.BANS, 1, FIRST, NOW - HOUR, 0).put("IPBAN", true);
        database.punished(FakeLiteBansDatabase.MUTES, 2, FIRST, NOW - HOUR, 0).put("SERVER_SCOPE", "lobby");

        JsonObject exported = export(ScopedPunishmentChoice.WIDEN, MAX_BYTES);

        JsonArray punishments = firstPlayer(exported).getAsJsonArray("punishments");
        assertEquals(2, punishments.size());
        assertEquals(0, progress.skipped());
        assertTrue(punishments.get(0).getAsJsonObject().getAsJsonObject("data").get("altBlocking").getAsBoolean());
        assertFalse(punishments.get(1).getAsJsonObject().getAsJsonObject("data").has("altBlocking"));
    }

    @Test
    void thePreviewCountsBothKindsAndRowsWithoutAnAccount() throws Exception {
        database.punished(FakeLiteBansDatabase.BANS, 1, FIRST, NOW, 0).put("IPBAN", true);
        database.punished(FakeLiteBansDatabase.BANS, 2, "#offline#", NOW, 0).put("IPBAN", true);
        database.punished(FakeLiteBansDatabase.MUTES, 3, SECOND, NOW, 0).put("SERVER_SCOPE", "survival");
        database.joined("CONSOLE", "Console", null, NOW);

        SourceInventory inventory = reader.inventory();

        assertEquals(2, inventory.getBans());
        assertEquals(1, inventory.getMutes());
        assertEquals(1, inventory.getIpWide());
        assertEquals(1, inventory.getServerScoped());
        assertEquals(1, inventory.getWithoutAccount());
        assertEquals(2, inventory.getPlayers());
    }

    @Test
    void warningsBecomeWarningNotesAndKicksBecomeKicks() throws Exception {
        database.punished(FakeLiteBansDatabase.WARNINGS, 5, FIRST, NOW - HOUR, 0).put("REASON", "Spam");
        Map<String, Object> removed = database.punished(FakeLiteBansDatabase.WARNINGS, 6, FIRST, NOW - HOUR, 0);
        removed.put("ACTIVE", false);
        removed.put("REMOVED_BY_NAME", "Admin");
        database.punished(FakeLiteBansDatabase.KICKS, 7, FIRST, NOW - HOUR, 0);

        JsonObject player = exportOnePlayer();

        JsonObject note = player.getAsJsonArray("notes").get(0).getAsJsonObject();
        assertEquals(1, player.getAsJsonArray("notes").size());
        assertEquals("WARNING", note.get("kind").getAsString());
        assertEquals("Spam", note.get("text").getAsString());
        assertEquals("litebans-warning-5", note.get("id").getAsString());
        JsonObject kick = onlyPunishment(player);
        assertEquals("litebans-kick-7", kick.get("_id").getAsString());
        assertEquals(0, kick.get("typeOrdinal").getAsInt());
        assertEquals(1, progress.skipped());
    }

    @Test
    void historyBecomesNamesAndAggregatedAddresses() throws Exception {
        database.joined(FIRST, "Joueur", "192.0.2.1", NOW - 2 * HOUR);
        database.joined(FIRST, "Joueur", "192.0.2.1", NOW - HOUR);

        JsonObject player = exportOnePlayer();

        assertEquals(1, player.getAsJsonArray("usernames").size());
        JsonObject address = player.getAsJsonArray("ipList").get(0).getAsJsonObject();
        assertEquals(Instant.ofEpochMilli(NOW - 2 * HOUR).toString(), address.get("firstLogin").getAsString());
        assertEquals(2, address.getAsJsonArray("logins").size());
    }

    @Test
    void historyStoredWithAnotherUuidSpellingIsReadAndExportedCanonically() throws Exception {
        String stored = FIRST.replace("-", "").toUpperCase(Locale.ROOT);
        database.joined(stored, "Joueur", "192.0.2.1", NOW - HOUR);
        database.punished(FakeLiteBansDatabase.BANS, 7, FIRST, NOW - HOUR, 0);

        JsonObject player = exportOnePlayer();

        assertEquals(FIRST, player.get("minecraftUuid").getAsString());
        assertEquals("Joueur", player.getAsJsonArray("usernames").get(0).getAsJsonObject().get("username").getAsString());
        assertEquals(1, player.getAsJsonArray("punishments").size());
    }

    @Test
    void oldHistoryIsCounted() throws Exception {
        database.joined(FIRST, "Old", "192.0.2.1", NOW - 2 * HOUR);
        database.joined(FIRST, "New", "192.0.2.1", NOW);

        assertEquals(1, reader.historyEntriesBefore(NOW - HOUR));
    }

    @Test
    void theFileIsUtf8() throws Exception {
        String reason = "Gr\u00fc\u00dfe \ud83c\udfae";
        database.punished(FakeLiteBansDatabase.BANS, 1, FIRST, NOW, 0).put("REASON", reason);

        assertEquals(reason, onlyPunishment(exportOnePlayer()).get("reason").getAsString());
    }

    @Test
    void anUnreadablePlayerIsCountedAndTheOthersAreExported() throws Exception {
        database.punished(FakeLiteBansDatabase.BANS, 1, FIRST, NOW, 0);
        database.punished(FakeLiteBansDatabase.BANS, 2, SECOND, NOW, 0);
        SourceInventory inventory = reader.inventory();
        database.makeUnreadable(FIRST);

        JsonObject exported = export(inventory, ScopedPunishmentChoice.WIDEN, MAX_BYTES);

        assertEquals(1, progress.failed());
        assertEquals(2, progress.handled());
        assertEquals(SECOND, firstPlayer(exported).get("minecraftUuid").getAsString());
        assertEquals(1, logger.warnings().size());
    }

    @Test
    void aLostDatabaseAbortsInsteadOfWritingATruncatedExport() throws Exception {
        database.punished(FakeLiteBansDatabase.BANS, 1, FIRST, NOW, 0);
        SourceInventory inventory = reader.inventory();
        database.goDown();

        assertEquals("import.database_failed", failureOf(inventory, MAX_BYTES).getMessageKey());
        assertEquals(0, progress.failed());
    }

    @Test
    void stoppingLiteBansAborts() throws Exception {
        database.punished(FakeLiteBansDatabase.BANS, 1, FIRST, NOW, 0);
        SourceInventory inventory = reader.inventory();
        database.makeUnreadable(FIRST);
        enabled = false;

        assertEquals("import.litebans_missing", failureOf(inventory, MAX_BYTES).getMessageKey());
    }

    @Test
    void whenEveryPlayerFailsTheExportFails() throws Exception {
        database.punished(FakeLiteBansDatabase.BANS, 1, FIRST, NOW, 0);
        SourceInventory inventory = reader.inventory();
        database.makeUnreadable(FIRST);

        assertEquals("import.every_player_failed", failureOf(inventory, MAX_BYTES).getMessageKey());
    }

    @Test
    void aFileOverTheLimitEndsAsTooLarge() throws Exception {
        database.punished(FakeLiteBansDatabase.BANS, 1, FIRST, NOW, 0);

        assertEquals(ImportEnding.FILE_TOO_LARGE, failureOf(reader.inventory(), 64).getEnding());
    }

    @Test
    void aCancelStopsBetweenPlayers() throws Exception {
        database.punished(FakeLiteBansDatabase.BANS, 1, FIRST, NOW, 0);
        SourceInventory inventory = reader.inventory();
        signal.set();

        assertEquals(ImportEnding.CANCELLED, failureOf(inventory, MAX_BYTES).getEnding());
    }

    private JsonObject exportOnePlayer() throws Exception {
        return firstPlayer(export(ScopedPunishmentChoice.WIDEN, MAX_BYTES));
    }

    private JsonObject export(ScopedPunishmentChoice choice, long maxBytes) throws Exception {
        return export(reader.inventory(), choice, maxBytes);
    }

    private JsonObject export(SourceInventory inventory, ScopedPunishmentChoice choice, long maxBytes) throws Exception {
        Path target = directory.resolve("export.json");
        exporter().export(target, inventory, choice, maxBytes, progress, Instant.ofEpochMilli(NOW));
        return new Gson().fromJson(new String(Files.readAllBytes(target), StandardCharsets.UTF_8), JsonObject.class);
    }

    private ImportFailure failureOf(SourceInventory inventory, long maxBytes) {
        return assertThrows(ImportFailure.class, () -> exporter().export(directory.resolve("export.json"), inventory,
                ScopedPunishmentChoice.WIDEN, maxBytes, progress, Instant.ofEpochMilli(NOW)));
    }

    private ImportExporter exporter() {
        return new ImportExporter(source, reader, signal, logger);
    }

    private static JsonObject firstPlayer(JsonObject exported) {
        return exported.getAsJsonArray("players").get(0).getAsJsonObject();
    }

    private static JsonObject onlyPunishment(JsonObject player) {
        JsonArray punishments = player.getAsJsonArray("punishments");
        assertEquals(1, punishments.size());
        return punishments.get(0).getAsJsonObject();
    }
}
