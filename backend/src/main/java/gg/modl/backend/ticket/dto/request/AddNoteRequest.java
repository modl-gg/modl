package gg.modl.backend.ticket.dto.request;

import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import java.util.List;

public record AddNoteRequest(
    @NotBlank @Size(max = RequestValidationLimits.TICKET_NOTE_TEXT_MAX_LENGTH) String text,
    @Nullable @Size(max = RequestValidationLimits.TICKET_REPLY_ATTACHMENTS_MAX_ENTRIES) List<Object> attachments
) {
}
