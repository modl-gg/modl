package gg.modl.backend.player.service;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.player.dto.request.CreateEvidenceRequest;
import gg.modl.backend.player.dto.request.CreateNoteRequest;
import gg.modl.backend.player.dto.request.CreatePunishmentRequest;
import org.jspecify.annotations.Nullable;
import java.util.Collection;

final class PunishmentCreationLimits {
    private static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";

    private PunishmentCreationLimits() {
    }

    static void require(CreatePunishmentRequest request) {
        requireCount("notes", request.notes(), RequestValidationLimits.PLAYER_PUNISHMENT_NOTES_MAX_ENTRIES);
        requireCount("evidence", request.evidence(), RequestValidationLimits.PLAYER_PUNISHMENT_EVIDENCE_MAX_ENTRIES);
        requireCount("attachedTicketIds", request.attachedTicketIds(), RequestValidationLimits.PLAYER_PUNISHMENT_TICKETS_MAX_ENTRIES);
        if (request.notes() != null) {
            for (CreateNoteRequest note : request.notes()) {
                requireLength("note text", note.text(), RequestValidationLimits.PLAYER_NOTE_TEXT_MAX_LENGTH);
            }
        }
        if (request.evidence() != null) {
            for (CreateEvidenceRequest evidence : request.evidence()) {
                requireLength("evidence text", evidence.text(), RequestValidationLimits.EVIDENCE_TEXT_MAX_LENGTH);
                requireLength("evidence URL", evidence.fileUrl(), RequestValidationLimits.EVIDENCE_URL_MAX_LENGTH);
                requireLength("evidence file name", evidence.fileName(), RequestValidationLimits.FILE_NAME_MAX_LENGTH);
            }
        }
        if (request.attachedTicketIds() != null) {
            for (String ticketId : request.attachedTicketIds()) {
                requireLength("ticket id", ticketId, RequestValidationLimits.ID_MAX_LENGTH);
            }
        }
    }

    private static void requireCount(String field, @Nullable Collection<?> values, int max) {
        if (values != null && values.size() > max) {
            throw new ValidationException("A punishment can be created with at most " + max + " " + field, INVALID_ARGUMENT);
        }
    }

    private static void requireLength(String field, @Nullable String value, int max) {
        if (value != null && value.codePointCount(0, value.length()) > max) {
            throw new ValidationException("The " + field + " must be at most " + max + " characters", INVALID_ARGUMENT);
        }
    }
}
