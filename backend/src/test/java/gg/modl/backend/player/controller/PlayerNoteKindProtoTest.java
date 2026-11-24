package gg.modl.backend.player.controller;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.player.data.PlayerNoteKind;
import org.junit.jupiter.api.Test;

class PlayerNoteKindProtoTest {
    @Test
    void everyStoredKindTravelsToTheWire() {
        assertThat(PlayerNoteKindProto.toProto(PlayerNoteKind.WARNING))
            .isEqualTo(gg.modl.proto.modl.v1.PlayerNoteKind.PLAYER_NOTE_KIND_WARNING);
        assertThat(PlayerNoteKindProto.toProto(PlayerNoteKind.NOTE))
            .isEqualTo(gg.modl.proto.modl.v1.PlayerNoteKind.PLAYER_NOTE_KIND_NOTE);
    }

    @Test
    void theWireKindIsReadBackAndAnUnnamedKindIsNone() {
        assertThat(PlayerNoteKindProto.fromProto(gg.modl.proto.modl.v1.PlayerNoteKind.PLAYER_NOTE_KIND_WARNING))
            .isEqualTo(PlayerNoteKind.WARNING);
        assertThat(PlayerNoteKindProto.fromProto(gg.modl.proto.modl.v1.PlayerNoteKind.PLAYER_NOTE_KIND_NOTE))
            .isEqualTo(PlayerNoteKind.NOTE);
        assertThat(PlayerNoteKindProto.fromProto(gg.modl.proto.modl.v1.PlayerNoteKind.PLAYER_NOTE_KIND_UNSPECIFIED)).isNull();
    }
}
