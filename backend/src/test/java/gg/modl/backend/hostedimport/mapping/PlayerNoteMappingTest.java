package gg.modl.backend.hostedimport.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.hostedimport.HostedArchiveFixture;
import gg.modl.backend.hostedimport.ImportTestContexts;
import gg.modl.backend.hostedimport.archive.ArchiveDocument;
import gg.modl.backend.hostedimport.archive.ExtendedJsonDecoder;
import gg.modl.backend.player.data.NoteEntry;
import gg.modl.backend.player.data.PlayerNoteKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerNoteMappingTest {
    private static final String UUID = "0b7e8a3c-6c55-4b3a-9d8a-222222222222";

    @TempDir
    Path directory;

    @Test
    void aHostedWarningNoteBecomesAWarningWithoutThePrefix() throws IOException {
        List<NoteEntry> notes = notes("{\"_id\":\"n1\",\"text\":\"WARNING: stop spamming\",\"issuerName\":\"Mod\","
            + "\"date\":{\"$date\":\"2026-01-01T00:00:00Z\"}}");

        assertThat(notes).singleElement().satisfies(note -> {
            assertThat(note.getKind()).isEqualTo(PlayerNoteKind.WARNING);
            assertThat(note.getText()).isEqualTo("stop spamming");
        });
    }

    @Test
    void anyOtherHostedNoteStaysANoteWordForWord() throws IOException {
        List<NoteEntry> notes = notes(
            "{\"_id\":\"n1\",\"text\":\"Gave a warning: spam\",\"date\":{\"$date\":\"2026-01-01T00:00:00Z\"}}",
            "{\"_id\":\"n2\",\"text\":\"warning: lower case\",\"date\":{\"$date\":\"2026-01-01T00:00:00Z\"}}");

        assertThat(notes).extracting(NoteEntry::getKind).containsOnly(PlayerNoteKind.NOTE);
        assertThat(notes).extracting(NoteEntry::getText).containsExactly("Gave a warning: spam", "warning: lower case");
    }

    private List<NoteEntry> notes(String... notes) throws IOException {
        ArchiveIndex index = ImportTestContexts.index(new HostedArchiveFixture(), directory);
        return new PlayerMapper(ImportTestContexts.context(index))
            .map(document("{\"_id\":\"p\",\"minecraftUuid\":\"" + UUID + "\",\"notes\":[" + String.join(",", notes) + "]}"))
            .orElseThrow()
            .snapshot()
            .player()
            .getNotes();
    }

    private static ArchiveDocument document(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return ArchiveDocument.of(ExtendedJsonDecoder.decodeLine(bytes, bytes.length));
    }
}
