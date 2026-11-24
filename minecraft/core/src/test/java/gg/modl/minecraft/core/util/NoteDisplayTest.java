package gg.modl.minecraft.core.util;

import gg.modl.minecraft.api.Note;
import gg.modl.minecraft.api.PlayerNoteKind;
import gg.modl.minecraft.core.support.MapLocaleManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NoteDisplayTest {
    private final MapLocaleManager locale = new MapLocaleManager().put(NoteDisplay.WARNING_BADGE_KEY, "[Warning] ");

    @Test
    void aWarningCarriesTheLocalizedBadge() {
        assertEquals("[Warning] stop spamming",
                NoteDisplay.text(locale, new Note("stop spamming", Instant.now(), "Mod", null, PlayerNoteKind.WARNING)));
    }

    @Test
    void aNoteThatLooksLikeAWarningIsShownAsTyped() {
        assertEquals("WARNING: just a note",
                NoteDisplay.text(locale, new Note("WARNING: just a note", Instant.now(), "Mod", null, PlayerNoteKind.NOTE)));
        assertEquals("plain", NoteDisplay.text(locale, new Note("plain", Instant.now(), "Mod", null)));
    }
}
