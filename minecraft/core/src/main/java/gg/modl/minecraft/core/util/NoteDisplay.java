package gg.modl.minecraft.core.util;

import gg.modl.minecraft.api.Note;
import gg.modl.minecraft.core.locale.LocaleManager;

public final class NoteDisplay {
    public static final String WARNING_BADGE_KEY = "print.notes.warning_badge";

    private NoteDisplay() {
    }

    public static String text(LocaleManager locale, Note note) {
        String text = note.getText() != null ? note.getText() : "";
        return note.isWarning() ? locale.getMessage(WARNING_BADGE_KEY) + text : text;
    }
}
