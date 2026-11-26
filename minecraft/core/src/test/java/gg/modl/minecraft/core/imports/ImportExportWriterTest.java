package gg.modl.minecraft.core.imports;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gg.modl.minecraft.core.imports.source.PunishmentKind;
import gg.modl.minecraft.core.imports.source.SourcePlayer;
import gg.modl.minecraft.core.imports.source.SourcePunishment;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ImportExportWriterTest {
    private static final String WARNING_TEXT = "No reason specified";
    private static final long ISSUED = 1_700_000_000_000L;

    @TempDir
    Path folder;

    @Test
    void aBanWithoutAReasonCarriesNeitherAReasonNorACopyInItsNotes() throws IOException {
        JsonObject ban = exportOf(punishment("ban", PunishmentKind.BAN, null)).getAsJsonArray("punishments").get(0).getAsJsonObject();

        assertFalse(ban.has("reason"));
        assertEquals(0, ban.getAsJsonArray("notes").size());
    }

    @Test
    void aBanWithAReasonKeepsIt() throws IOException {
        JsonObject ban = exportOf(punishment("ban", PunishmentKind.BAN, "griefing")).getAsJsonArray("punishments").get(0).getAsJsonObject();

        assertEquals("griefing", ban.get("reason").getAsString());
        assertEquals("griefing", ban.getAsJsonArray("notes").get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void aWarningWithoutTextGetsTheWarningTextAndAnEmptyNoteIsDropped() throws IOException {
        JsonArray notes = exportOf(
                punishment("warning", PunishmentKind.WARNING, null),
                punishment("note", PunishmentKind.NOTE, null)).getAsJsonArray("notes");

        assertEquals(1, notes.size());
        assertEquals("WARNING", notes.get(0).getAsJsonObject().get("kind").getAsString());
        assertEquals(WARNING_TEXT, notes.get(0).getAsJsonObject().get("text").getAsString());
    }

    private JsonObject exportOf(SourcePunishment... punishments) throws IOException {
        Path target = folder.resolve("export.json");
        List<SourcePunishment> list = Arrays.asList(punishments);
        SourcePlayer player = new SourcePlayer("dfc1d5fe-106e-47b2-93b0-f2a9b0dc1f86", Collections.emptyList(), Collections.emptyList(), list);
        try (ImportExportWriter writer = new ImportExportWriter("test", target, Long.MAX_VALUE, 1, Instant.ofEpochMilli(ISSUED), WARNING_TEXT)) {
            writer.write(player, list, false);
            writer.finish();
        }
        JsonObject root = JsonParser.parseString(new String(Files.readAllBytes(target), StandardCharsets.UTF_8)).getAsJsonObject();
        return root.getAsJsonArray("players").get(0).getAsJsonObject();
    }

    private static SourcePunishment punishment(String id, PunishmentKind kind, @Nullable String reason) {
        return SourcePunishment.builder().id(id).kind(kind).reason(reason).issuedAt(ISSUED).build();
    }
}
