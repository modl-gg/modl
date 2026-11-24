package gg.modl.backend.player.data;

import gg.modl.backend.infrastructure.exception.ValidationException;
import org.jspecify.annotations.Nullable;

public enum PlayerNoteKind {
    NOTE,
    WARNING;

    public static final String KIND_REQUIRED = "PLAYER_NOTE_KIND_REQUIRED";

    public static PlayerNoteKind require(@Nullable PlayerNoteKind requested) {
        if (requested == null) {
            throw new ValidationException("A player note must say whether it is a note or a warning", KIND_REQUIRED);
        }
        return requested;
    }
}
