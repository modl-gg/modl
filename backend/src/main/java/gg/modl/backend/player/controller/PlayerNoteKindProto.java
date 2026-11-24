package gg.modl.backend.player.controller;

import gg.modl.backend.player.data.PlayerNoteKind;
import org.jspecify.annotations.Nullable;

final class PlayerNoteKindProto {
    private PlayerNoteKindProto() {
    }

    @Nullable
    static PlayerNoteKind fromProto(gg.modl.proto.modl.v1.PlayerNoteKind kind) {
        return switch (kind) {
            case PLAYER_NOTE_KIND_NOTE -> PlayerNoteKind.NOTE;
            case PLAYER_NOTE_KIND_WARNING -> PlayerNoteKind.WARNING;
            case PLAYER_NOTE_KIND_UNSPECIFIED, UNRECOGNIZED -> null;
        };
    }

    static gg.modl.proto.modl.v1.PlayerNoteKind toProto(@Nullable Object kind) {
        return kind == PlayerNoteKind.WARNING
            ? gg.modl.proto.modl.v1.PlayerNoteKind.PLAYER_NOTE_KIND_WARNING
            : gg.modl.proto.modl.v1.PlayerNoteKind.PLAYER_NOTE_KIND_NOTE;
    }
}
