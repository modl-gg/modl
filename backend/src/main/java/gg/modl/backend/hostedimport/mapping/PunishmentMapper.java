package gg.modl.backend.hostedimport.mapping;

import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_EVIDENCE;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_MODIFICATIONS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_NOTES;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.hostedimport.archive.ArchiveDocument;
import gg.modl.backend.hostedimport.mapping.ImportedIds.IdKind;
import gg.modl.backend.hostedimport.model.ImportSubject;
import gg.modl.backend.hostedimport.model.IssueCode;
import gg.modl.backend.migration.service.LegacyModificationTypes;
import gg.modl.backend.player.data.punishment.EnforcementCategoryResolver;
import gg.modl.backend.player.data.punishment.IdentityRestriction;
import gg.modl.backend.player.data.punishment.IssuedReason;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentData;
import gg.modl.backend.player.data.punishment.PunishmentDataView;
import gg.modl.backend.player.data.punishment.PunishmentEvidence;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.settings.data.PunishmentType;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

final class PunishmentMapper {
    private static final String SYSTEM_ISSUER = "System";
    private static final String NO_DATA_REASON = "Imported without data; modl.gg did not enforce it";
    private static final String IMPORTED_ID = "importedId";
    private static final int KICK_ORDINAL = 0;
    private static final String TEXT_EVIDENCE = "text";
    private static final String LINK_EVIDENCE = "link";
    private static final Set<String> STRUCTURED_SOURCE_KEYS = Set.of(PunishmentData.PENDING_ACKNOWLEDGEMENT, IMPORTED_ID);

    private final ImportContext context;
    private final ImportFit fit;

    PunishmentMapper(ImportContext context) {
        this.context = context;
        this.fit = context.fit();
    }

    List<Punishment> punishments(List<ArchiveDocument> sources, String playerUuid) {
        List<Punishment> punishments = new ArrayList<>();
        Map<String, ArchiveDocument> seen = new LinkedHashMap<>();
        Set<String> usedIds = new HashSet<>();
        for (int index = 0; index < sources.size(); index++) {
            ArchiveDocument source = sources.get(index);
            String sourceId = source.id();
            ArchiveDocument earlier = sourceId == null ? null : seen.get(sourceId);
            if (earlier != null && earlier.fields().equals(source.fields())) {
                continue;
            }
            String id = id(sourceId, playerUuid, index, earlier != null);
            if (!usedIds.add(id)) {
                id = context.ids().derive(IdKind.PUNISHMENT, playerUuid + "#" + index, Objects.requireNonNullElse(sourceId, ""));
                usedIds.add(id);
            }
            if (sourceId != null) {
                seen.putIfAbsent(sourceId, source);
            }
            punishments.add(punishment(source, sourceId, id));
            context.issues().imported(ImportSubject.PUNISHMENTS);
        }
        return punishments;
    }

    private String id(@Nullable String sourceId, String playerUuid, int index, boolean duplicateWithinPlayer) {
        if (sourceId == null) {
            return context.ids().derive(IdKind.PUNISHMENT, playerUuid + "#" + index, "");
        }
        String id = duplicateWithinPlayer
            ? context.punishmentIds().withinPlayerDuplicate(sourceId, playerUuid, index)
            : context.punishmentIds().of(sourceId, playerUuid);
        if (!id.equals(sourceId)) {
            context.issues().report(IssueCode.ID_REMAPPED, sourceId);
        }
        return id;
    }

    private Punishment punishment(ArchiveDocument source, @Nullable String sourceId, String id) {
        Integer ordinalOrNull = source.intValue("typeOrdinal");
        if (ordinalOrNull == null) {
            context.issues().report(IssueCode.VALUE_DEFAULTED, id);
        }
        int ordinal = ordinalOrNull == null ? KICK_ORDINAL : ordinalOrNull;
        List<ArchiveDocument> modificationSources = source.documents("modifications");
        List<ArchiveDocument> noteSources = source.documents("notes");
        Instant issued = issued(source, modificationSources, noteSources);
        Instant started = source.instant("started");
        if (ordinal == KICK_ORDINAL && started == null) {
            started = issued;
            context.issues().report(IssueCode.KICK_MARKED_DELIVERED, id);
        }
        List<PunishmentModification> modifications = modifications(modificationSources, id, issued);
        List<PunishmentNote> notes = notes(noteSources, id, issued);
        Optional<ArchiveDocument> sourceData = source.document("data");
        Map<String, Object> data = sourceData.map(this::data).orElseGet(LinkedHashMap::new);
        if (sourceData.isEmpty()) {
            modifications.add(new PunishmentModification(context.ids().derive(IdKind.MODIFICATION, id, "no-data"),
                PunishmentModificationType.SYSTEM_PARDON.name(), issued, SYSTEM_ISSUER, null, NO_DATA_REASON, null, null, null));
            context.issues().report(IssueCode.PUNISHMENT_WITHOUT_DATA, id);
        }
        if (sourceId != null && !sourceId.equals(id)) {
            data.put(IMPORTED_ID, fit.cut(sourceId, 1000));
        }
        String issuerSourceId = source.idOf("issuerId");
        String issuerName = fit.columnIfPresent(PUNISHMENTS.ISSUER_NAME, context.issuers().name(issuerSourceId, source.string("issuerName")));
        String issuerId = context.issuers().staffId(issuerSourceId);
        Punishment punishment = new Punishment(
            id,
            ordinal,
            issuerName,
            issuerId,
            PunishmentIssuerKind.ofImported(issuerId, issuerName),
            issued,
            started,
            modifications,
            notes,
            evidence(source.list("evidence"), issued),
            ticketIds(source.list("attachedTicketIds")),
            data);
        restrictions(punishment);
        restoreIssuedReason(punishment);
        punishment.setNotes(IssuedReason.withoutReasonCopy(punishment.getNotes(), punishment.data().reason()));
        if (punishment.hasRollback()) {
            context.issues().report(IssueCode.PUNISHMENT_ROLLBACK_ENDS, id);
        }
        return punishment;
    }

    private Instant issued(ArchiveDocument source, List<ArchiveDocument> modifications, List<ArchiveDocument> notes) {
        Instant issued = source.instant("issued");
        if (issued != null) {
            return issued;
        }
        Instant started = source.instant("started");
        if (started != null) {
            return started;
        }
        return Stream.concat(modifications.stream(), notes.stream())
            .map(child -> child.instant("date"))
            .filter(Objects::nonNull)
            .min(Comparator.naturalOrder())
            .orElse(context.exportTime());
    }

    private List<PunishmentModification> modifications(List<ArchiveDocument> sources, String punishmentId, Instant issued) {
        List<PunishmentModification> modifications = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < sources.size(); index++) {
            ArchiveDocument source = sources.get(index);
            String type = Objects.requireNonNullElse(
                fit.columnIfPresent(PUNISHMENT_MODIFICATIONS.TYPE, LegacyModificationTypes.current(source.string("type"))), "UNKNOWN");
            String id = childId(IdKind.MODIFICATION, punishmentId, source.id(), index, 100, ids);
            String issuerSourceId = source.idOf("issuerId");
            Map<String, Object> data = source.document("data").map(this::freeMap).orElse(null);
            modifications.add(new PunishmentModification(
                id,
                type,
                Objects.requireNonNullElse(source.instant("date"), issued),
                fit.columnIfPresent(PUNISHMENT_MODIFICATIONS.ISSUER_NAME, context.issuers().name(issuerSourceId, source.string("issuerName"))),
                fit.columnIfPresent(PUNISHMENT_MODIFICATIONS.ISSUER_ID, context.issuers().staffId(issuerSourceId)),
                Objects.requireNonNullElse(rewrittenText(source.string("reason")), ""),
                ImportedDurations.of(source.raw("effectiveDuration"), context.issues()),
                fit.columnIfPresent(PUNISHMENT_MODIFICATIONS.APPEAL_TICKET_ID, context.ticketIds().of(source.idOf("appealTicketId"))),
                data));
        }
        return modifications;
    }

    private List<PunishmentNote> notes(List<ArchiveDocument> sources, String punishmentId, Instant issued) {
        List<PunishmentNote> notes = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < sources.size(); index++) {
            ArchiveDocument source = sources.get(index);
            String issuerSourceId = source.idOf("issuerId");
            notes.add(new PunishmentNote(
                childId(IdKind.PUNISHMENT_NOTE, punishmentId, source.id(), index, 36, ids),
                fit.text(context.urls().rewriteText(Objects.requireNonNullElse(source.string("text"), ""))),
                Objects.requireNonNullElse(source.instant("date"), issued),
                fit.columnIfPresent(PUNISHMENT_NOTES.ISSUER_NAME, context.issuers().name(issuerSourceId, source.string("issuerName"))),
                fit.columnIfPresent(PUNISHMENT_NOTES.ISSUER_ID, context.issuers().staffId(issuerSourceId))));
        }
        return notes;
    }

    private String childId(IdKind kind, String parentId, @Nullable String sourceId, int index, int limit, Set<String> used) {
        String id = sourceId != null && used.add(sourceId) ? context.ids().adopt(kind, parentId, sourceId, limit) : null;
        if (id == null || (!id.equals(sourceId) && !used.add(id))) {
            id = context.ids().derive(kind, parentId + "#" + index, Objects.requireNonNullElse(sourceId, ""));
            used.add(id);
        }
        return id;
    }

    private List<PunishmentEvidence> evidence(List<@Nullable Object> sources, Instant issued) {
        List<PunishmentEvidence> evidence = new ArrayList<>();
        for (@Nullable Object item : sources) {
            if (item instanceof String text) {
                evidence.add(new PunishmentEvidence(fit.text(context.urls().rewriteText(text)), null, TEXT_EVIDENCE, null, null,
                    issued, null, null, null));
                continue;
            }
            Optional<ArchiveDocument> document = ArchiveDocument.of(item);
            if (document.isEmpty()) {
                continue;
            }
            ArchiveDocument source = document.get();
            String text = source.string("text");
            String rawUrl = source.string("url");
            String url = context.urls().safeUrl(rawUrl);
            if (rawUrl != null && !rawUrl.isBlank() && url == null) {
                text = text == null || text.isBlank() ? rawUrl : text + " " + rawUrl;
            }
            String uploaderSourceId = source.idOf("uploadedById");
            String type = fit.columnIfPresent(PUNISHMENT_EVIDENCE.TYPE, source.string("type"));
            evidence.add(new PunishmentEvidence(
                fit.textIfPresent(text == null ? null : context.urls().rewriteText(text)),
                url,
                type != null && !type.isBlank() ? type : (url != null ? LINK_EVIDENCE : TEXT_EVIDENCE),
                fit.columnIfPresent(PUNISHMENT_EVIDENCE.UPLOADED_BY, context.issuers().name(uploaderSourceId, source.string("uploadedBy"))),
                fit.columnIfPresent(PUNISHMENT_EVIDENCE.UPLOADED_BY_ID, context.issuers().staffId(uploaderSourceId)),
                Objects.requireNonNullElse(source.instant("uploadedAt"), issued),
                fit.columnIfPresent(PUNISHMENT_EVIDENCE.FILE_NAME, source.string("fileName")),
                fit.columnIfPresent(PUNISHMENT_EVIDENCE.FILE_TYPE, source.string("fileType")),
                source.longValue("fileSize")));
        }
        return evidence;
    }

    private List<String> ticketIds(List<@Nullable Object> sources) {
        return sources.stream()
            .map(ArchiveDocument::idString)
            .map(context.ticketIds()::of)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    }

    private Map<String, Object> data(ArchiveDocument source) {
        Map<String, Object> data = new LinkedHashMap<>();
        source.fields().forEach((key, value) -> {
            if (!PunishmentData.STRUCTURED_KEYS.contains(key) && !STRUCTURED_SOURCE_KEYS.contains(key)) {
                data.put(key, context.urls().rewriteTree(value));
            }
        });
        putText(data, PunishmentData.STATUS, fit.columnIfPresent(PUNISHMENTS.STATUS, source.text("status")));
        putText(data, PunishmentData.REASON, rewrittenText(source.string("reason")));
        putText(data, PunishmentData.SEVERITY, fit.columnIfPresent(PUNISHMENTS.SEVERITY, source.text("severity")));
        putText(data, PunishmentData.OFFENSE_LEVEL, fit.columnIfPresent(PUNISHMENTS.OFFENSE_LEVEL, source.text("offenseLevel")));
        putText(data, PunishmentData.BLOCKED_NAME, fit.columnIfPresent(PUNISHMENTS.BLOCKED_NAME, source.text("blockedName")));
        putText(data, PunishmentData.BLOCKED_SKIN, fit.columnIfPresent(PUNISHMENTS.BLOCKED_SKIN, source.text("blockedSkin")));
        putText(data, PunishmentData.APPEAL_OUTCOME, fit.columnIfPresent(PUNISHMENTS.APPEAL_OUTCOME, source.text("appealOutcome")));
        putText(data, PunishmentData.APPEAL_TICKET_ID, fit.columnIfPresent(PUNISHMENTS.APPEAL_TICKET_ID,
            context.ticketIds().of(source.idOf("appealTicketId"))));
        putText(data, PunishmentData.ENFORCEMENT_CATEGORY, source.string("enforcementCategory"));
        Long duration = ImportedDurations.of(source.raw("duration"), context.issues());
        if (duration != null) {
            data.put(PunishmentData.DURATION, duration);
        }
        data.put(PunishmentData.ALT_BLOCKING, source.bool("altBlocking"));
        data.put(PunishmentData.WIPE_AFTER_EXPIRY, source.bool("wipeAfterExpiry"));
        if (source.bool("statWipeCompleted")) {
            PunishmentDataView.ofMap(data).markStatWipeCompleted(
                Objects.requireNonNullElse(source.instant("statWipeCompletedAt"), context.acceptedAt()));
        }
        String parentUuid = uuidText(source.raw("linkedBanParentUuid"));
        if (parentUuid != null) {
            data.put(PunishmentData.LINKED_BAN_PARENT_UUID, parentUuid);
        }
        String linkedBanId = context.punishmentIds().reference(source.idOf("linkedBanId"), parentUuid);
        putText(data, PunishmentData.LINKED_BAN_ID, linkedBanId);
        return data;
    }

    private void restrictions(Punishment punishment) {
        PunishmentDataView data = punishment.data();
        String stored = data.asMap() != null && data.asMap().get(PunishmentData.ENFORCEMENT_CATEGORY) instanceof String text ? text : null;
        PunishmentType type = context.types().byOrdinal(punishment.getTypeOrdinal());
        data.setEnforcementCategory(category(punishment, type, stored));
        if (type != null && type.restrictsIdentity()) {
            data.removeOptions();
        }
    }

    private ResolvedCategory category(Punishment punishment, @Nullable PunishmentType type, @Nullable String stored) {
        PunishmentDataView data = punishment.data();
        if (punishment.getTypeOrdinal() == KICK_ORDINAL) {
            return ResolvedCategory.NONE;
        }
        if (ResolvedCategory.NONE.name().equals(stored)) {
            context.issues().report(IssueCode.PUNISHMENT_STORED_NONE, punishment.getId());
            return ResolvedCategory.NONE;
        }
        if (type != null && type.restrictsIdentity()) {
            if (!IdentityRestriction.canLift(type, data)) {
                context.issues().report(IssueCode.PUNISHMENT_RESTRICTION_UNKNOWN, punishment.getId());
            }
            return EnforcementCategoryResolver.resolve(type, data);
        }
        if (ResolvedCategory.BAN.name().equals(stored) || ResolvedCategory.MUTE.name().equals(stored)) {
            if (type == null) {
                context.issues().report(IssueCode.PUNISHMENT_DELETED_TYPE_ENFORCED, punishment.getId());
            }
            return ResolvedCategory.fromStored(stored);
        }
        if (type != null) {
            return EnforcementCategoryResolver.resolve(type, data);
        }
        context.issues().report(IssueCode.PUNISHMENT_CATEGORY_UNKNOWN, punishment.getId());
        return ResolvedCategory.NONE;
    }

    private void restoreIssuedReason(Punishment punishment) {
        String stored = punishment.data().reason();
        if (stored != null && !stored.isBlank()) {
            return;
        }
        IssuedReason.fromLegacyNotes(punishment.getTypeOrdinal(), punishment.getIssued(), punishment.getNotes())
            .ifPresent(reason -> punishment.data().setReason(reason));
    }

    private Map<String, Object> freeMap(ArchiveDocument document) {
        return context.urls().rewriteMap(document.fields());
    }

    @Nullable
    private String rewrittenText(@Nullable String text) {
        return text == null ? null : fit.text(context.urls().rewriteText(text));
    }

    @Nullable
    private static String uuidText(@Nullable Object value) {
        return Optional.ofNullable(ArchiveDocument.uuidOf(value)).map(Object::toString).orElse(null);
    }

    private static void putText(Map<String, Object> data, String key, @Nullable String value) {
        if (value != null) {
            data.put(key, value);
        } else {
            data.remove(key);
        }
    }
}
