package gg.modl.backend.migration.service;

import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_EVIDENCE;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_MODIFICATIONS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_NOTES;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_TICKETS;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.infrastructure.validation.SafeUrls;
import gg.modl.backend.migration.validation.MigrationValidator;
import gg.modl.backend.player.data.punishment.EnforcementCategoryResolver;
import gg.modl.backend.player.data.punishment.IssuedReason;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentData;
import gg.modl.backend.player.data.punishment.PunishmentDataView;
import gg.modl.backend.player.data.punishment.PunishmentEvidence;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import gg.modl.backend.player.data.punishment.PunishmentStatus;
import gg.modl.backend.settings.data.PunishmentType;
import org.jspecify.annotations.Nullable;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class ImportedPunishments {
    private static final String UNKNOWN_ISSUER = "Unknown";
    private static final String SYSTEM_ISSUER = "System";
    private static final int MAX_SOURCE_ID_LENGTH = 1000;
    private static final int MAX_NOTE_LENGTH = 5000;
    private static final int MAX_REASON_LENGTH = 1000;
    private static final int MAX_URL_LENGTH = 2000;

    private final MigrationValidator validator;
    private final ImportedValues values;

    ImportedPunishments(MigrationValidator validator, ImportedValues values) {
        this.validator = validator;
        this.values = values;
    }

    @Nullable
    Punishment toPunishment(UUID playerUuid, Map<?, ?> source, Map<Integer, PunishmentType> typesByOrdinal, Instant importedAt) {
        Instant issued = validator.parseDate(source.get("issued"));
        if (issued == null) {
            return null;
        }
        int typeOrdinal = typeOrdinal(source.get("typeOrdinal"));
        String issuerId = values.identifier(source.get("issuerId"), TextLimits.limit(PUNISHMENTS.ISSUER_ID));
        String sourceIssuerName = issuerLabel(source.get("issuerName"));
        PunishmentIssuerKind issuerKind = PunishmentIssuerKind.ofImported(issuerId, sourceIssuerName);
        String issuerName = issuerName(sourceIssuerName, issuerId);

        Map<String, Object> data = normalisedData(source, importedAt);
        List<PunishmentModification> modifications = modifications(source.get("modifications"));
        addPardonWhenInactiveAtSource(data, modifications, issued);

        String id = values.label(source.get("id") != null ? source.get("id") : source.get("_id"), MAX_SOURCE_ID_LENGTH);
        if (id == null) {
            id = derivedId(playerUuid, typeOrdinal, issued, issuerName, (String) data.get(PunishmentData.REASON));
        }

        PunishmentDataView view = PunishmentDataView.ofMap(data);
        PunishmentType type = typesByOrdinal.get(typeOrdinal);
        view.setEnforcementCategory(EnforcementCategoryResolver.resolve(type, view));
        if (type != null && type.restrictsIdentity()) {
            view.removeOptions();
        }
        List<PunishmentNote> sourceNotes = notes(source.get("notes"));
        restoreIssuedReason(view, typeOrdinal, issued, sourceNotes);
        List<PunishmentNote> notes = IssuedReason.withoutReasonCopy(sourceNotes, view.reason());

        return new Punishment(
            id,
            typeOrdinal,
            issuerName,
            issuerId,
            issuerKind,
            issued,
            validator.parseDate(source.get("started")),
            modifications,
            notes,
            evidence(source.get("evidence"), importedAt),
            ticketIds(source.get("attachedTicketIds")),
            data);
    }

    private Map<String, Object> normalisedData(Map<?, ?> source, Instant importedAt) {
        Map<String, Object> data = ImportedValues.entries(source.get("data"));
        fitLabel(data, PunishmentData.STATUS, TextLimits.limit(PUNISHMENTS.STATUS));
        fitLabel(data, PunishmentData.SEVERITY, TextLimits.limit(PUNISHMENTS.SEVERITY));
        fitLabel(data, PunishmentData.OFFENSE_LEVEL, TextLimits.limit(PUNISHMENTS.OFFENSE_LEVEL));
        fitLabel(data, PunishmentData.BLOCKED_NAME, TextLimits.limit(PUNISHMENTS.BLOCKED_NAME));
        fitLabel(data, PunishmentData.BLOCKED_SKIN, TextLimits.limit(PUNISHMENTS.BLOCKED_SKIN));
        fitLabel(data, PunishmentData.APPEAL_OUTCOME, TextLimits.limit(PUNISHMENTS.APPEAL_OUTCOME));
        fitIdentifier(data, PunishmentData.APPEAL_TICKET_ID, TextLimits.limit(PUNISHMENTS.APPEAL_TICKET_ID));
        fitIdentifier(data, PunishmentData.LINKED_BAN_ID, TextLimits.limit(PUNISHMENTS.LINKED_BAN_ID));
        fitIdentifier(data, PunishmentData.LINKED_BAN_PARENT_UUID, TextLimits.limit(PUNISHMENTS.LINKED_BAN_PARENT_UUID));

        String reason = values.label(source.get("reason"), MAX_REASON_LENGTH);
        ImportedValues.putOrRemove(data, PunishmentData.REASON,
            reason != null ? reason : values.label(data.get(PunishmentData.REASON), Integer.MAX_VALUE));

        Object duration = source.get("duration") instanceof Number number ? number : data.get(PunishmentData.DURATION);
        ImportedValues.putOrRemove(data, PunishmentData.DURATION, ImportedValues.wholeNumber(duration));

        Instant wipedAt = validator.parseDate(data.remove(PunishmentData.STAT_WIPE_COMPLETED_AT));
        if (PunishmentDataView.ofMap(data).statWipeCompleted()) {
            PunishmentDataView.ofMap(data).markStatWipeCompleted(wipedAt != null ? wipedAt : importedAt);
        }
        return data;
    }

    private void restoreIssuedReason(PunishmentDataView view, int typeOrdinal, Instant issued, List<PunishmentNote> notes) {
        String stored = view.reason();
        if (stored != null && !stored.isBlank()) {
            return;
        }
        IssuedReason.fromLegacyNotes(typeOrdinal, issued, notes)
            .map(reason -> values.label(reason, MAX_REASON_LENGTH))
            .ifPresent(view::setReason);
    }

    private void fitLabel(Map<String, Object> data, String key, int limit) {
        ImportedValues.putOrRemove(data, key, values.label(data.get(key), limit));
    }

    private void fitIdentifier(Map<String, Object> data, String key, int limit) {
        ImportedValues.putOrRemove(data, key, values.identifier(data.get(key), limit));
    }

    private void addPardonWhenInactiveAtSource(Map<String, Object> data, List<PunishmentModification> modifications,
                                               Instant issued) {
        Object active = data.get("active");
        Object pardonedBy = data.get("pardonedBy");
        boolean inactiveAtSource = Boolean.FALSE.equals(active)
            || (active instanceof String text && "false".equalsIgnoreCase(text))
            || pardonedBy != null;
        boolean alreadyEnded = modifications.stream()
            .anyMatch(modification -> PunishmentModificationType.endsEnforcement(modification.type()));
        if (modifications.stream().anyMatch(modification -> PunishmentModificationType.isPardon(modification.type()))) {
            PunishmentDataView.ofMap(data).setStatus(PunishmentStatus.PARDONED);
        }
        if (!inactiveAtSource || alreadyEnded) {
            return;
        }
        Instant pardonedAt = validator.parseDate(data.get("pardonedDate"));
        if (pardonedAt == null) {
            pardonedAt = validator.parseDate(data.get("removedAt"));
        }
        String issuer = values.label(pardonedBy, TextLimits.limit(PUNISHMENT_MODIFICATIONS.ISSUER_NAME));
        modifications.add(new PunishmentModification(
            IdGenerator.generateShortId(),
            PunishmentModificationType.SYSTEM_PARDON.name(),
            pardonedAt != null ? pardonedAt : issued,
            issuer != null ? issuer : SYSTEM_ISSUER,
            null,
            "Imported as already removed/inactive",
            null,
            null,
            null));
        PunishmentDataView.ofMap(data).setStatus(PunishmentStatus.PARDONED);
    }

    private List<PunishmentModification> modifications(@Nullable Object source) {
        List<PunishmentModification> result = new ArrayList<>();
        for (Object item : ImportedValues.items(source)) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            PunishmentModificationType type = PunishmentModificationType.fromName(
                LegacyModificationTypes.current(values.text(map.get("type"), MAX_SOURCE_ID_LENGTH)));
            Instant date = validator.parseDate(map.get("date"));
            if (type == null || date == null) {
                continue;
            }
            String id = values.identifier(map.get("id"), TextLimits.limit(PUNISHMENT_MODIFICATIONS.ID));
            Map<String, Object> data = map.get("data") instanceof Map<?, ?> nested ? ImportedValues.entries(nested) : null;
            result.add(new PunishmentModification(
                id != null ? id : IdGenerator.generateShortId(),
                type.name(),
                date,
                values.label(map.get("issuerName"), TextLimits.limit(PUNISHMENT_MODIFICATIONS.ISSUER_NAME)),
                values.identifier(map.get("issuerId"), TextLimits.limit(PUNISHMENT_MODIFICATIONS.ISSUER_ID)),
                Objects.requireNonNullElse(values.text(map.get("reason"), MAX_REASON_LENGTH), ""),
                ImportedValues.wholeNumber(map.get("effectiveDuration")),
                values.identifier(map.get("appealTicketId"), TextLimits.limit(PUNISHMENT_MODIFICATIONS.APPEAL_TICKET_ID)),
                data));
        }
        return result;
    }

    private List<PunishmentNote> notes(@Nullable Object source) {
        List<PunishmentNote> result = new ArrayList<>();
        for (Object item : ImportedValues.items(source)) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String text = values.text(map.get("text"), MAX_NOTE_LENGTH);
            Instant date = validator.parseDate(map.get("date"));
            if (text != null && date != null) {
                String issuerId = values.identifier(map.get("issuerId"), TextLimits.limit(PUNISHMENT_NOTES.ISSUER_ID));
                result.add(new PunishmentNote(IdGenerator.generateShortId(), text, date,
                    issuerName(issuerLabel(map.get("issuerName")), issuerId), issuerId));
            }
        }
        return result;
    }

    private List<PunishmentEvidence> evidence(@Nullable Object source, Instant importedAt) {
        List<PunishmentEvidence> result = new ArrayList<>();
        for (Object item : ImportedValues.items(source)) {
            if (item instanceof String) {
                String text = values.label(item, MAX_NOTE_LENGTH);
                if (text != null) {
                    result.add(new PunishmentEvidence(text, null, "text", null, null, importedAt, null, null, null));
                }
            } else if (item instanceof Map<?, ?> map) {
                String url = values.text(map.get("url"), MAX_URL_LENGTH);
                String type = values.label(map.get("type"), TextLimits.limit(PUNISHMENT_EVIDENCE.TYPE));
                Instant uploadedAt = validator.parseDate(map.get("uploadedAt"));
                result.add(new PunishmentEvidence(
                    values.text(map.get("text"), MAX_NOTE_LENGTH),
                    SafeUrls.isSafe(url) ? url : null,
                    type != null ? type : "link",
                    values.label(map.get("uploadedBy"), TextLimits.limit(PUNISHMENT_EVIDENCE.UPLOADED_BY)),
                    values.label(map.get("uploadedById"), TextLimits.limit(PUNISHMENT_EVIDENCE.UPLOADED_BY)),
                    uploadedAt != null ? uploadedAt : importedAt,
                    values.label(map.get("fileName"), TextLimits.limit(PUNISHMENT_EVIDENCE.FILE_NAME)),
                    values.label(map.get("fileType"), TextLimits.limit(PUNISHMENT_EVIDENCE.FILE_TYPE)),
                    ImportedValues.wholeNumber(map.get("fileSize"))));
            }
        }
        return result;
    }

    private List<String> ticketIds(@Nullable Object source) {
        List<String> result = new ArrayList<>();
        for (Object item : ImportedValues.items(source)) {
            String ticketId = values.identifier(item, TextLimits.limit(PUNISHMENT_TICKETS.TICKET_ID));
            if (ticketId != null) {
                result.add(ticketId);
            }
        }
        return result;
    }

    @Nullable
    private String issuerLabel(@Nullable Object name) {
        return values.label(name, TextLimits.limit(PUNISHMENTS.ISSUER_NAME));
    }

    @Nullable
    private static String issuerName(@Nullable String label, @Nullable String issuerId) {
        return label == null && issuerId == null ? UNKNOWN_ISSUER : label;
    }

    private static int typeOrdinal(@Nullable Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException notAnOrdinal) {
                return 0;
            }
        }
        return 0;
    }

    private static String derivedId(UUID playerUuid, int typeOrdinal, Instant issued, @Nullable String issuerName,
                                    @Nullable String reason) {
        String content = playerUuid + "|" + typeOrdinal + "|" + issued.toEpochMilli() + "|" + issuerName + "|"
            + Objects.requireNonNullElse(reason, "");
        return "import-" + UUID.nameUUIDFromBytes(content.getBytes(StandardCharsets.UTF_8));
    }
}
