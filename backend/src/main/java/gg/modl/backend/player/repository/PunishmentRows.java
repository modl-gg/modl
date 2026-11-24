package gg.modl.backend.player.repository;

import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_EVIDENCE;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_MODIFICATIONS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_NOTES;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_TICKETS;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.database.JsonColumns;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.jooq.tables.records.PunishmentEvidenceRecord;
import gg.modl.backend.database.jooq.tables.records.PunishmentModificationsRecord;
import gg.modl.backend.database.jooq.tables.records.PunishmentNotesRecord;
import gg.modl.backend.database.jooq.tables.records.PunishmentTicketsRecord;
import gg.modl.backend.database.jooq.tables.records.PunishmentsRecord;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentData;
import gg.modl.backend.player.data.punishment.PunishmentDataView;
import gg.modl.backend.player.data.punishment.PunishmentEvidence;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import gg.modl.backend.player.data.punishment.PunishmentState;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class PunishmentRows {
    private final JsonColumns json;

    PunishmentRows(JsonColumns json) {
        this.json = json;
    }

    PunishmentsRecord header(String playerId, Punishment punishment, ResolvedCategory category) {
        PunishmentDataView data = punishment.data();
        PunishmentsRecord row = new PunishmentsRecord();
        row.setId(TextLimits.require(PUNISHMENTS.ID, punishment.getId()));
        row.setPlayerId(TextLimits.require(PUNISHMENTS.PLAYER_ID, playerId));
        row.setTypeOrdinal(punishment.getTypeOrdinal());
        row.setIssuerName(TextLimits.requireIfPresent(PUNISHMENTS.ISSUER_NAME, punishment.getIssuerName()));
        row.setIssuerNameLower(TextLimits.requireIfPresent(PUNISHMENTS.ISSUER_NAME_LOWER, lowerCase(punishment.getIssuerName())));
        row.setIssuerId(TextLimits.requireIfPresent(PUNISHMENTS.ISSUER_ID, punishment.getIssuerId()));
        row.setIssuerKind(TextLimits.require(PUNISHMENTS.ISSUER_KIND, punishment.getIssuerKind().name()));
        row.setIssuedAt(punishment.getIssued());
        row.setStartedAt(punishment.getStarted());
        row.setStatus(TextLimits.requireIfPresent(PUNISHMENTS.STATUS, data.status()));
        row.setDurationMs(data.duration());
        row.setReason(data.reason());
        row.setSeverity(TextLimits.requireIfPresent(PUNISHMENTS.SEVERITY, data.severity()));
        row.setOffenseLevel(TextLimits.requireIfPresent(PUNISHMENTS.OFFENSE_LEVEL, data.offenseLevel()));
        row.setEnforcementCategory(TextLimits.require(PUNISHMENTS.ENFORCEMENT_CATEGORY, category.name()));
        row.setAltBlocking(data.altBlocking());
        row.setWipeAfterExpiry(data.wipeAfterExpiry());
        row.setStatWipeCompletedAt(statWipeCompletedAt(punishment));
        row.setLinkedBanId(TextLimits.requireIfPresent(PUNISHMENTS.LINKED_BAN_ID, data.linkedBanId()));
        row.setLinkedBanParentUuid(TextLimits.requireIfPresent(PUNISHMENTS.LINKED_BAN_PARENT_UUID, data.linkedBanParentUuid()));
        row.setBlockedName(TextLimits.requireIfPresent(PUNISHMENTS.BLOCKED_NAME, data.blockedName()));
        row.setBlockedSkin(TextLimits.requireIfPresent(PUNISHMENTS.BLOCKED_SKIN, data.blockedSkin()));
        row.setAppealOutcome(TextLimits.requireIfPresent(PUNISHMENTS.APPEAL_OUTCOME, data.appealOutcome()));
        row.setAppealTicketId(TextLimits.requireIfPresent(PUNISHMENTS.APPEAL_TICKET_ID, data.appealTicketId()));
        Map<String, Object> extra = data.extra();
        row.setExtraJson(extra.isEmpty() ? null : json.write(extra));
        row.setActiveUntilAt(PunishmentState.activeUntil(punishment));
        row.setModifiedAt(modifiedAt(punishment.getModifications()));
        row.setVersion(0);
        return row;
    }

    PunishmentModificationsRecord modification(String punishmentId, PunishmentModification modification) {
        PunishmentModificationsRecord row = new PunishmentModificationsRecord();
        row.setPunishmentId(TextLimits.require(PUNISHMENT_MODIFICATIONS.PUNISHMENT_ID, punishmentId));
        row.setId(TextLimits.require(PUNISHMENT_MODIFICATIONS.ID, modification.id()));
        row.setType(TextLimits.require(PUNISHMENT_MODIFICATIONS.TYPE, modification.type()));
        row.setIssuerName(TextLimits.requireIfPresent(PUNISHMENT_MODIFICATIONS.ISSUER_NAME, modification.issuerName()));
        row.setIssuerId(TextLimits.requireIfPresent(PUNISHMENT_MODIFICATIONS.ISSUER_ID, modification.issuerId()));
        row.setReason(Objects.requireNonNullElse(modification.reason(), ""));
        row.setEffectiveDurationMs(modification.effectiveDuration());
        row.setAppealTicketId(TextLimits.requireIfPresent(PUNISHMENT_MODIFICATIONS.APPEAL_TICKET_ID, modification.appealTicketId()));
        row.setDataJson(json.writeIfPresent(modification.data()));
        row.setCreatedAt(modification.date());
        return row;
    }

    PunishmentNotesRecord note(String punishmentId, PunishmentNote note) {
        PunishmentNotesRecord row = new PunishmentNotesRecord();
        row.setPunishmentId(TextLimits.require(PUNISHMENT_NOTES.PUNISHMENT_ID, punishmentId));
        row.setId(TextLimits.require(PUNISHMENT_NOTES.ID, note.id()));
        row.setBody(Objects.requireNonNullElse(note.text(), ""));
        row.setIssuerName(TextLimits.requireIfPresent(PUNISHMENT_NOTES.ISSUER_NAME, note.issuerName()));
        row.setIssuerId(TextLimits.requireIfPresent(PUNISHMENT_NOTES.ISSUER_ID, note.issuerId()));
        row.setCreatedAt(note.date());
        return row;
    }

    PunishmentEvidenceRecord evidence(String punishmentId, PunishmentEvidence evidence) {
        PunishmentEvidenceRecord row = new PunishmentEvidenceRecord();
        row.setPunishmentId(TextLimits.require(PUNISHMENT_EVIDENCE.PUNISHMENT_ID, punishmentId));
        row.setType(TextLimits.require(PUNISHMENT_EVIDENCE.TYPE, evidence.type()));
        row.setBody(evidence.text());
        row.setUrl(evidence.url());
        row.setUploadedBy(TextLimits.requireIfPresent(PUNISHMENT_EVIDENCE.UPLOADED_BY, evidence.uploadedBy()));
        row.setUploadedById(TextLimits.requireIfPresent(PUNISHMENT_EVIDENCE.UPLOADED_BY_ID, evidence.uploadedById()));
        row.setFileName(TextLimits.requireIfPresent(PUNISHMENT_EVIDENCE.FILE_NAME, evidence.fileName()));
        row.setFileType(TextLimits.requireIfPresent(PUNISHMENT_EVIDENCE.FILE_TYPE, evidence.fileType()));
        row.setFileSize(evidence.fileSize());
        row.setUploadedAt(evidence.uploadedAt());
        return row;
    }

    List<PunishmentTicketsRecord> tickets(String punishmentId, List<String> ticketIds) {
        String parentId = TextLimits.require(PUNISHMENT_TICKETS.PUNISHMENT_ID, punishmentId);
        return ticketIds.stream()
            .filter(Objects::nonNull)
            .map(ticketId -> TextLimits.require(PUNISHMENT_TICKETS.TICKET_ID, ticketId))
            .distinct()
            .sorted()
            .map(ticketId -> {
                PunishmentTicketsRecord row = new PunishmentTicketsRecord();
                row.setPunishmentId(parentId);
                row.setTicketId(ticketId);
                return row;
            })
            .toList();
    }

    Punishment toPunishment(PunishmentsRecord row, List<PunishmentModification> modifications, List<PunishmentNote> notes,
                            List<PunishmentEvidence> evidence, List<String> ticketIds) {
        return new Punishment(
            row.getId(),
            row.getTypeOrdinal(),
            row.getIssuerName(),
            row.getIssuerId(),
            PunishmentIssuerKind.fromStored(row.getIssuerKind()),
            row.getIssuedAt(),
            row.getStartedAt(),
            new ArrayList<>(modifications),
            new ArrayList<>(notes),
            new ArrayList<>(evidence),
            new ArrayList<>(ticketIds),
            wireData(row));
    }

    PunishmentModification toModification(PunishmentModificationsRecord row) {
        return new PunishmentModification(
            row.getId(),
            row.getType(),
            row.getCreatedAt(),
            row.getIssuerName(),
            row.getIssuerId(),
            row.getReason(),
            row.getEffectiveDurationMs(),
            row.getAppealTicketId(),
            row.getDataJson() == null ? null : json.readMap(row.getDataJson()));
    }

    PunishmentNote toNote(PunishmentNotesRecord row) {
        return new PunishmentNote(row.getId(), row.getBody(), row.getCreatedAt(), row.getIssuerName(), row.getIssuerId());
    }

    PunishmentEvidence toEvidence(PunishmentEvidenceRecord row) {
        return new PunishmentEvidence(
            row.getBody(),
            row.getUrl(),
            row.getType(),
            row.getUploadedBy(),
            row.getUploadedById(),
            row.getUploadedAt(),
            row.getFileName(),
            row.getFileType(),
            row.getFileSize());
    }

    @Nullable
    static Instant modifiedAt(List<PunishmentModification> modifications) {
        return modifications.stream()
            .map(PunishmentModification::date)
            .filter(Objects::nonNull)
            .max(Comparator.naturalOrder())
            .orElse(null);
    }

    @Nullable
    static String lowerCase(@Nullable String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    @Nullable
    private static Instant statWipeCompletedAt(Punishment punishment) {
        PunishmentDataView data = punishment.data();
        if (!data.statWipeCompleted()) {
            return null;
        }
        return Objects.requireNonNullElse(data.statWipeCompletedAt(), punishment.getIssued());
    }

    private Map<String, Object> wireData(PunishmentsRecord row) {
        Map<String, Object> data = new HashMap<>(json.readMap(row.getExtraJson()));
        data.put(PunishmentData.ENFORCEMENT_CATEGORY, row.getEnforcementCategory());
        data.put(PunishmentData.ALT_BLOCKING, row.getAltBlocking());
        data.put(PunishmentData.WIPE_AFTER_EXPIRY, row.getWipeAfterExpiry());
        putIfPresent(data, PunishmentData.STATUS, row.getStatus());
        putIfPresent(data, PunishmentData.DURATION, row.getDurationMs());
        putIfPresent(data, PunishmentData.REASON, row.getReason());
        putIfPresent(data, PunishmentData.SEVERITY, row.getSeverity());
        putIfPresent(data, PunishmentData.OFFENSE_LEVEL, row.getOffenseLevel());
        putIfPresent(data, PunishmentData.LINKED_BAN_ID, row.getLinkedBanId());
        putIfPresent(data, PunishmentData.LINKED_BAN_PARENT_UUID, row.getLinkedBanParentUuid());
        putIfPresent(data, PunishmentData.BLOCKED_NAME, row.getBlockedName());
        putIfPresent(data, PunishmentData.BLOCKED_SKIN, row.getBlockedSkin());
        putIfPresent(data, PunishmentData.APPEAL_OUTCOME, row.getAppealOutcome());
        putIfPresent(data, PunishmentData.APPEAL_TICKET_ID, row.getAppealTicketId());
        if (row.getStatWipeCompletedAt() != null) {
            data.put(PunishmentData.STAT_WIPE_COMPLETED, true);
            data.put(PunishmentData.STAT_WIPE_COMPLETED_AT, row.getStatWipeCompletedAt());
        }
        return data;
    }

    private static void putIfPresent(Map<String, Object> data, String key, @Nullable Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }
}
