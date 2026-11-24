package gg.modl.backend.player.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.player.service.PlayerNotePermission;
import gg.modl.backend.role.service.PermissionService;
import org.junit.jupiter.api.Test;

class PlayerNoteKindTest {
    @Test
    void eachKindNeedsItsOwnPermission() {
        assertThat(PlayerNotePermission.requiredFor(PlayerNoteKind.NOTE)).isEqualTo(PermissionService.PLAYER_NOTE);
        assertThat(PlayerNotePermission.requiredFor(PlayerNoteKind.WARNING)).isEqualTo(PermissionService.PLAYER_WARN);
    }

    @Test
    void theKindMustBeNamed() {
        assertThatThrownBy(() -> PlayerNoteKind.require(null))
            .isInstanceOfSatisfying(ValidationException.class,
                refused -> assertThat(refused.getMachineCode()).isEqualTo(PlayerNoteKind.KIND_REQUIRED));
    }

    @Test
    void aHostedWarningBecomesAWarningWithoutItsPrefix() {
        assertThat(LegacyNoteText.of("WARNING: stop spamming"))
            .isEqualTo(new LegacyNoteText(PlayerNoteKind.WARNING, "stop spamming"));
    }

    @Test
    void anyOtherHostedNoteStaysANoteWordForWord() {
        for (String text : new String[] {"warning: lower case", "Gave a warning: no more spam", " WARNING: indented", "WARNING:spam", ""}) {
            assertThat(LegacyNoteText.of(text)).isEqualTo(new LegacyNoteText(PlayerNoteKind.NOTE, text));
        }
    }
}
