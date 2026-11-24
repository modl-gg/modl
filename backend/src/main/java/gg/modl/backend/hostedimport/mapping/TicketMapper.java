package gg.modl.backend.hostedimport.mapping;

import static gg.modl.backend.database.jooq.Tables.TICKETS;
import static gg.modl.backend.database.jooq.Tables.TICKET_ASSIGNEES;
import static gg.modl.backend.database.jooq.Tables.TICKET_NOTES;
import static gg.modl.backend.database.jooq.Tables.TICKET_REPLIES;
import static gg.modl.backend.database.jooq.Tables.TICKET_TAGS;

import gg.modl.backend.ai.data.AIAnalysisResult;
import gg.modl.backend.database.JsonColumns;
import gg.modl.backend.hostedimport.archive.ArchiveDocument;
import gg.modl.backend.hostedimport.mapping.ImportedIds.IdKind;
import gg.modl.backend.hostedimport.model.ImportSubject;
import gg.modl.backend.hostedimport.model.IssueCode;
import gg.modl.backend.replay.util.ReplayReferenceUtil;
import gg.modl.backend.ticket.data.AppealWorkflowStatus;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketNote;
import gg.modl.backend.ticket.data.TicketPriority;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.repository.TicketAttachmentLimits;
import org.jspecify.annotations.Nullable;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

public final class TicketMapper {
    private static final String IMPORTED_ORIGINAL = "importedOriginal";
    private static final String PUNISHMENT_ID = "punishmentId";
    private static final String LINKED_PUNISHMENT_ID = "linkedPunishmentId";
    private static final String PLAYER_UUID = "playerUuid";
    private static final String HOSTED_STAFF_NOTE_TYPE = "staff-note";
    private static final int REPLY_ID_LIMIT = 36;
    private static final int REPLAY_ID_LIMIT = 64;
    private static final JsonColumns JSON = new JsonColumns();

    private final ImportContext context;
    private final ImportFit fit;
    private final Function<String, String> replayLinks;
    private final Function<Ticket, String> accessKeys;

    public TicketMapper(ImportContext context, Function<String, String> replayLinks, Function<Ticket, String> accessKeys) {
        this.context = context;
        this.fit = context.fit();
        this.replayLinks = replayLinks;
        this.accessKeys = accessKeys;
    }

    public Optional<Ticket> map(ArchiveDocument source) {
        return mapSourced(source).map(SourcedTicket::ticket);
    }

    public Optional<SourcedTicket> mapSourced(ArchiveDocument source) {
        String sourceId = source.id();
        String id = sourceId == null ? null : context.ticketIds().of(sourceId);
        if (sourceId == null || id == null) {
            context.issues().report(IssueCode.RECORD_REJECTED, "ticket without id");
            context.issues().skipped(ImportSubject.TICKETS);
            return Optional.empty();
        }
        if (!id.equals(sourceId)) {
            context.issues().report(IssueCode.ID_REMAPPED, sourceId);
        }
        Map<String, Object> originals = new LinkedHashMap<>();
        boolean locked = source.bool("locked");
        TicketCategory type = enumOrNull(TicketCategory::fromCanonicalId, source.string("type"), "type", originals);
        if (type == null) {
            type = typeFromPrefix(id);
        }
        TicketStatus status = enumOrNull(TicketStatus::fromCanonicalId, source.string("status"), "status", originals);
        TicketPriority priority = enumOrNull(TicketPriority::fromCanonicalId, source.string("priority"), "priority", originals);
        AppealWorkflowStatus workflow = enumOrNull(AppealWorkflowStatus::fromCanonicalId, source.string("appealWorkflowStatus"),
            "appealWorkflowStatus", originals);
        Instant created = Objects.requireNonNullElse(source.instant("created"), context.exportTime());
        Map<String, Object> data = source.document("data").map(document -> context.urls().rewriteUntrustedMap(document.fields()))
            .orElseGet(LinkedHashMap::new);
        mapPunishmentReferences(data);
        if (!originals.isEmpty()) {
            data.put(IMPORTED_ORIGINAL, originals);
        }
        String replayId = replayId(source);
        Ticket ticket = Ticket.builder()
            .id(id)
            .type(type)
            .subject(rewrittenTextIfPresent(source.string("subject")))
            .status(status != null ? status : (locked ? TicketStatus.CLOSED : TicketStatus.OPEN))
            .priority(priority != null ? priority : TicketPriority.NORMAL)
            .appealWorkflowStatus(workflow)
            .creatorUuid(uuid(source.raw("creatorUuid")))
            .creatorName(fit.columnIfPresent(TICKETS.CREATOR_NAME, source.text("creatorName")))
            .creatorAvatar(context.urls().safeUrl(source.string("creatorAvatar")))
            .reportedPlayer(fit.columnIfPresent(TICKETS.REPORTED_PLAYER_NAME, source.text("reportedPlayer")))
            .reportedPlayerUuid(uuid(source.raw("reportedPlayerUuid")))
            .tags(distinct(source.list("tags"), value -> fit.column(TICKET_TAGS.TAG, value)))
            .assignedTo(distinct(source.list("assignedTo"), value -> fit.column(TICKET_ASSIGNEES.ASSIGNEE, value)))
            .locked(locked)
            .hidden(source.bool("hidden"))
            .emailAuthEnabled(source.bool("emailAuthEnabled"))
            .created(created)
            .updatedAt(Objects.requireNonNullElse(source.instant("updatedAt"), created))
            .formData(source.document("formData").map(document -> context.urls().rewriteUntrustedMap(document.fields())).orElse(null))
            .data(data)
            .replayId(replayId)
            .replayUrl(replayId == null ? null : replayLinks.apply(replayId))
            .replies(replies(threadReplies(source.documents("replies")), id, created))
            .notes(notes(source.documents("notes"), staffNoteReplies(source.documents("replies")), created))
            .chatMessages(chatMessages(source.list("chatMessages")))
            .aiAnalysis(aiAnalysis(source.document("aiAnalysis").orElse(null)))
            .build();
        ticket.setPublicAccessKey(accessKeys.apply(ticket));
        context.issues().imported(ImportSubject.TICKETS);
        return Optional.of(new SourcedTicket(sourceId, id, ticket));
    }

    private void mapPunishmentReferences(Map<String, Object> data) {
        String scope = data.get(PLAYER_UUID) instanceof String uuid ? uuid(uuid) : null;
        if (data.get(PUNISHMENT_ID) instanceof String punishmentId) {
            data.put(PUNISHMENT_ID, context.punishmentIds().reference(punishmentId, scope));
        }
        if (data.get(LINKED_PUNISHMENT_ID) instanceof String linked) {
            data.put(LINKED_PUNISHMENT_ID, context.punishmentIds().reference(linked, null));
        }
    }

    @Nullable
    private String replayId(ArchiveDocument source) {
        String id = source.idOf("replayId");
        if (id == null || id.isBlank()) {
            id = ReplayReferenceUtil.extractReplayId(source.string("replayUrl"));
        }
        return id == null || id.isBlank() ? null : context.ids().adopt(IdKind.REPLAY, "", id, REPLAY_ID_LIMIT);
    }

    private List<TicketReply> replies(List<ArchiveDocument> sources, String ticketId, Instant created) {
        List<TicketReply> replies = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int index = 0; index < sources.size(); index++) {
            ArchiveDocument source = sources.get(index);
            String sourceId = source.id();
            String id = sourceId != null && used.add(sourceId)
                ? context.ids().adopt(IdKind.REPLY, ticketId, sourceId, REPLY_ID_LIMIT)
                : context.ids().derive(IdKind.REPLY, ticketId + "#" + index, Objects.requireNonNullElse(sourceId, ""));
            used.add(id);
            replies.add(TicketReply.builder()
                .id(id)
                .name(fit.columnIfPresent(TICKET_REPLIES.AUTHOR_NAME, source.text("name")))
                .avatar(context.urls().safeUrl(source.string("avatar")))
                .content(fit.text(context.urls().rewriteText(Objects.requireNonNullElse(source.string("content"), ""))))
                .type(fit.columnIfPresent(TICKET_REPLIES.TYPE, source.string("type")))
                .created(Objects.requireNonNullElse(source.instant("created"), created))
                .staff(source.bool("staff"))
                .action(fit.columnIfPresent(TICKET_REPLIES.ACTION, source.string("action")))
                .attachments(attachments(source.list("attachments")))
                .creatorIdentifier(fit.columnIfPresent(TICKET_REPLIES.CREATOR_IDENTIFIER, source.text("creatorIdentifier")))
                .build());
        }
        return replies;
    }

    private static List<ArchiveDocument> threadReplies(List<ArchiveDocument> sources) {
        return sources.stream().filter(source -> !isStaffNoteReply(source)).toList();
    }

    private static List<ArchiveDocument> staffNoteReplies(List<ArchiveDocument> sources) {
        return sources.stream().filter(TicketMapper::isStaffNoteReply).toList();
    }

    private static boolean isStaffNoteReply(ArchiveDocument source) {
        String type = source.string("type");
        return type != null && HOSTED_STAFF_NOTE_TYPE.equalsIgnoreCase(type.trim());
    }

    private List<TicketNote> notes(List<ArchiveDocument> noteSources, List<ArchiveDocument> staffNoteReplies, Instant created) {
        List<TicketNote> notes = new ArrayList<>();
        for (ArchiveDocument source : noteSources) {
            notes.add(note(source.string("text"), source.text("issuerName"), source.string("issuerAvatar"),
                source.instant("date"), source.list("attachments"), created));
        }
        for (ArchiveDocument source : staffNoteReplies) {
            notes.add(note(source.string("content"), source.text("name"), source.string("avatar"),
                source.instant("created"), source.list("attachments"), created));
        }
        notes.sort(Comparator.comparing(TicketNote::getDate));
        return notes;
    }

    private TicketNote note(@Nullable String text, @Nullable String issuerName, @Nullable String issuerAvatar,
                            @Nullable Instant date, List<@Nullable Object> attachments, Instant created) {
        return TicketNote.builder()
            .text(fit.text(context.urls().rewriteText(Objects.requireNonNullElse(text, ""))))
            .issuerName(fit.columnIfPresent(TICKET_NOTES.ISSUER_NAME, issuerName))
            .issuerAvatar(context.urls().safeUrl(issuerAvatar))
            .date(Objects.requireNonNullElse(date, created))
            .attachments(attachments(attachments))
            .build();
    }

    private List<Object> attachments(List<@Nullable Object> sources) {
        List<Object> attachments = new ArrayList<>();
        int total = 0;
        for (@Nullable Object source : sources) {
            Object rewritten = context.urls().rewriteUntrustedTree(source);
            int size = JSON.write(rewritten == null ? "" : rewritten).getBytes(StandardCharsets.UTF_8).length;
            if (size > TicketAttachmentLimits.MAX_ITEM_BYTES || total + size > TicketAttachmentLimits.MAX_TOTAL_BYTES) {
                context.issues().report(IssueCode.VALUE_TRUNCATED, "attachment");
                continue;
            }
            total += size;
            attachments.add(rewritten);
        }
        return attachments;
    }

    @Nullable
    private List<Ticket.ChatMessage> chatMessages(List<@Nullable Object> sources) {
        if (sources.isEmpty()) {
            return null;
        }
        List<Ticket.ChatMessage> messages = new ArrayList<>();
        for (@Nullable Object source : sources) {
            if (source instanceof String content) {
                messages.add(Ticket.ChatMessage.builder().content(rewrittenText(content)).build());
                continue;
            }
            ArchiveDocument.of(source).ifPresent(message -> messages.add(Ticket.ChatMessage.builder()
                .content(rewrittenText(Objects.requireNonNullElse(message.string("content"), "")))
                .timestamp(message.instant("timestamp"))
                .sender(fit.cutIfPresent(message.text("sender"), 100))
                .build()));
        }
        return messages;
    }

    @Nullable
    private AIAnalysisResult aiAnalysis(@Nullable ArchiveDocument source) {
        if (source == null) {
            return null;
        }
        AIAnalysisResult.SuggestedAction action = source.document("suggestedAction")
            .flatMap(TicketMapper::suggestedAction)
            .orElse(null);
        AIAnalysisResult result = new AIAnalysisResult(
            Objects.requireNonNullElse(fit.textIfPresent(source.string("analysis")), ""),
            action,
            Objects.requireNonNullElse(source.instant("createdAt"), context.exportTime()),
            Objects.requireNonNullElse(fit.textIfPresent(source.string("rawResponse")), ""));
        Long confidence = source.longValue("confidence");
        if (source.raw("confidence") instanceof Double value && Double.isFinite(value)) {
            result.setConfidence(value);
        } else if (confidence != null) {
            result.setConfidence(confidence.doubleValue());
        }
        result.setWasAppliedAutomatically(source.bool("wasAppliedAutomatically"));
        result.setAppliedBy(fit.cutIfPresent(source.text("appliedBy"), 254));
        result.setDismissed(source.bool("dismissed"));
        return result;
    }

    private static Optional<AIAnalysisResult.SuggestedAction> suggestedAction(ArchiveDocument suggested) {
        Integer punishmentTypeId = suggested.intValue("punishmentTypeId");
        return punishmentTypeId == null ? Optional.empty() : Optional.of(new AIAnalysisResult.SuggestedAction(punishmentTypeId,
            Objects.requireNonNullElse(suggested.string("severity"), "regular")));
    }

    private List<String> distinct(List<@Nullable Object> values, Function<String, String> fitted) {
        return values.stream()
            .map(value -> value instanceof String text ? fitted.apply(text) : null)
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .toList();
    }

    @Nullable
    private <E> E enumOrNull(Function<String, E> parse, @Nullable String value, String field, Map<String, Object> originals) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return parse.apply(value);
        } catch (IllegalArgumentException e) {
            originals.put(field, fit.cut(value, 64));
            context.issues().report(IssueCode.VALUE_DEFAULTED, field);
            return null;
        }
    }

    private static TicketCategory typeFromPrefix(String id) {
        String prefix = id.contains("-") ? id.substring(0, id.indexOf('-')).toUpperCase(Locale.ROOT) : "";
        return Arrays.stream(TicketCategory.values())
            .filter(category -> category.getTicketPrefix().equals(prefix))
            .findFirst()
            .orElse(TicketCategory.SUPPORT);
    }

    private String rewrittenText(String text) {
        return fit.text(context.urls().rewriteText(text));
    }

    private @Nullable String rewrittenTextIfPresent(@Nullable String text) {
        return text == null ? null : rewrittenText(text);
    }

    @Nullable
    private static String uuid(@Nullable Object value) {
        UUID uuid = ArchiveDocument.uuidOf(value);
        return uuid == null ? null : uuid.toString();
    }

    public record SourcedTicket(String sourceId, String mappedId, Ticket ticket) {
    }
}
