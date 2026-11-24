package gg.modl.backend.player.service;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.player.data.punishment.PunishmentEvidence;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import gg.modl.backend.player.dto.request.CreateEvidenceRequest;
import gg.modl.backend.player.dto.request.CreateNoteRequest;
import gg.modl.backend.player.dto.request.CreatePunishmentRequest;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class PunishmentEntries {
    static final String SYSTEM_ISSUER = "System";

    private static final String TEXT_EVIDENCE = "text";

    private PunishmentEntries() {
    }

    static PunishmentNote note(String text, Instant at, @Nullable String issuerName, @Nullable String issuerId) {
        return new PunishmentNote(IdGenerator.generateShortId(), text, at, issuerName, issuerId);
    }

    static PunishmentNote systemNote(String text, Instant at) {
        return note(text, at, SYSTEM_ISSUER, null);
    }

    static PunishmentModification modification(PunishmentModificationType type, Instant at, @Nullable String issuerName,
                                               @Nullable String issuerId, String reason, @Nullable Long effectiveDuration) {
        return new PunishmentModification(IdGenerator.generateShortId(), type.name(), at, issuerName, issuerId, reason,
            effectiveDuration, null, null);
    }

    static List<PunishmentNote> creationNotes(CreatePunishmentRequest request, Instant at) {
        String issuerId = request.issuerId();
        List<PunishmentNote> notes = new ArrayList<>();
        if (request.notes() != null) {
            for (CreateNoteRequest requested : request.notes()) {
                notes.add(note(requested.text(), at, PunishmentMapper.storedName(issuerId, request.issuerName()), issuerId));
            }
        }
        return notes;
    }

    static List<PunishmentEvidence> creationEvidence(CreatePunishmentRequest request, Instant at) {
        if (request.evidence() == null) {
            return List.of();
        }
        List<PunishmentEvidence> evidence = new ArrayList<>();
        for (CreateEvidenceRequest requested : request.evidence()) {
            if (requested.fileSize() != null && requested.fileSize() < 0) {
                throw new ValidationException("An evidence file size cannot be negative");
            }
            evidence.add(new PunishmentEvidence(
                requested.text(),
                requested.fileUrl(),
                requested.type() != null ? requested.type() : TEXT_EVIDENCE,
                PunishmentMapper.storedName(request.issuerId(), request.issuerName()),
                request.issuerId(),
                at,
                requested.fileName(),
                requested.fileType(),
                requested.fileSize()));
        }
        return evidence;
    }

    static List<PunishmentNote> linkedBanNotes(Instant at) {
        return List.of(systemNote("Automatically issued linked ban due to alt-blocking ban on linked account", at));
    }
}
