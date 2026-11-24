package gg.modl.backend.player.dto.request;

import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateNoteRequest(
    @NotBlank @Size(max = RequestValidationLimits.PLAYER_NOTE_TEXT_MAX_LENGTH) String text
) {
}
