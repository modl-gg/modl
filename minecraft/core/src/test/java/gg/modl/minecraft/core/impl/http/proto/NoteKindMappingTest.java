package gg.modl.minecraft.core.impl.http.proto;

import com.google.gson.Gson;
import gg.modl.minecraft.api.Note;
import gg.modl.minecraft.api.PlayerNoteKind;
import gg.modl.proto.modl.v1.NoteEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoteKindMappingTest {
    @Test
    void aV3WarningEntryBecomesAWarningNote() {
        Note note = PlayerProtoMapper.toNote(NoteEntry.newBuilder()
                .setText("stop spamming")
                .setKind(gg.modl.proto.modl.v1.PlayerNoteKind.PLAYER_NOTE_KIND_WARNING)
                .build());

        assertTrue(note.isWarning());
        assertEquals("stop spamming", note.getText());
    }

    @Test
    void aV3EntryWithoutAKindIsANote() {
        assertFalse(PlayerProtoMapper.toNote(NoteEntry.newBuilder().setText("watch").build()).isWarning());
    }

    @Test
    void theV2JsonKindIsReadAndAMissingKindIsANote() {
        Gson gson = new Gson();

        assertEquals(PlayerNoteKind.WARNING, gson.fromJson("{\"text\":\"spam\",\"kind\":\"WARNING\"}", Note.class).getKind());
        assertEquals(PlayerNoteKind.NOTE, gson.fromJson("{\"text\":\"watch\"}", Note.class).getKind());
    }
}
