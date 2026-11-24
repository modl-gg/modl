package gg.modl.backend.ticket.repository;

import static gg.modl.backend.database.jooq.Tables.TICKETS;
import static gg.modl.backend.database.jooq.Tables.TICKET_NOTES;
import static gg.modl.backend.database.jooq.Tables.TICKET_REPLIES;

import gg.modl.backend.ai.data.AIAnalysisResult;
import gg.modl.backend.database.JsonColumns;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.jooq.tables.records.TicketNotesRecord;
import gg.modl.backend.database.jooq.tables.records.TicketRepliesRecord;
import gg.modl.backend.database.jooq.tables.records.TicketsRecord;
import gg.modl.backend.infrastructure.util.UuidUtils;
import gg.modl.backend.ticket.data.AppealSubject;
import gg.modl.backend.ticket.data.AppealWorkflowStatus;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketNote;
import gg.modl.backend.ticket.data.TicketPriority;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.data.TicketStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.jooq.Field;
import org.jooq.TableField;
import tools.jackson.core.type.TypeReference;

final class TicketRows {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<Object>> ATTACHMENTS = new TypeReference<>() {
    };
    private static final TypeReference<List<Ticket.ChatMessage>> CHAT_MESSAGES = new TypeReference<>() {
    };

    private final JsonColumns json;

    TicketRows(JsonColumns json) {
        this.json = json;
    }

    TicketsRecord header(Ticket ticket) {
        TicketsRecord row = new TicketsRecord();
        row.setId(TextLimits.require(TICKETS.ID, ticket.getId()));
        row.setType(TextLimits.require(TICKETS.TYPE, ticket.getType().getId()));
        row.setSubject(ticket.getSubject());
        row.setStatus(TextLimits.require(TICKETS.STATUS, ticket.getStatus().getId()));
        row.setPriority(TextLimits.require(TICKETS.PRIORITY, ticket.getPriority().getId()));
        row.setCreatorUuid(
            TextLimits.requireIfPresent(TICKETS.CREATOR_UUID, UuidUtils.normalizeIfPresent(ticket.getCreatorUuid())));
        row.setCreatorName(TextLimits.requireIfPresent(TICKETS.CREATOR_NAME, ticket.getCreatorName()));
        row.setCreatorAvatar(ticket.getCreatorAvatar());
        row.setReportedPlayerName(TextLimits.requireIfPresent(TICKETS.REPORTED_PLAYER_NAME, ticket.getReportedPlayer()));
        row.setReportedPlayerUuid(TextLimits.requireIfPresent(
            TICKETS.REPORTED_PLAYER_UUID, UuidUtils.normalizeIfPresent(ticket.getReportedPlayerUuid())));
        row.setIsLocked(ticket.isLocked());
        row.setIsHidden(ticket.isHidden());
        row.setEmailAuthEnabled(ticket.isEmailAuthEnabled());
        row.setAppealWorkflowStatus(
            TextLimits.requireIfPresent(TICKETS.APPEAL_WORKFLOW_STATUS, idOf(ticket.getAppealWorkflowStatus())));
        row.setAppealPunishmentId(appealPunishmentId(ticket.getData()));
        row.setReplayUrl(ticket.getReplayUrl());
        row.setReplayId(reference(TICKETS.REPLAY_ID, ticket.getReplayId()));
        row.setPublicAccessKey(TextLimits.require(TICKETS.PUBLIC_ACCESS_KEY, ticket.getPublicAccessKey()));
        row.setFormDataJson(json.writeIfPresent(ticket.getFormData()));
        row.setDataJson(json.writeIfPresent(ticket.getData()));
        row.setChatMessagesJson(json.writeIfPresent(ticket.getChatMessages()));
        row.setAiAnalysisJson(analysisJson(ticket.getAiAnalysis()));
        row.setReplyCount(ticket.getReplies().size());
        row.setCreatedAt(ticket.getCreated());
        row.setUpdatedAt(ticket.getUpdatedAt() == null ? ticket.getCreated() : ticket.getUpdatedAt());
        row.setVersion(0);
        return row;
    }

    TicketRepliesRecord reply(String ticketId, TicketReply reply, Instant now) {
        TicketRepliesRecord row = new TicketRepliesRecord();
        row.setTicketId(TextLimits.require(TICKET_REPLIES.TICKET_ID, ticketId));
        row.setId(TextLimits.require(TICKET_REPLIES.ID, reply.getId()));
        String authorName = reply.getName();
        if (authorName != null) {
            row.setAuthorName(TextLimits.require(TICKET_REPLIES.AUTHOR_NAME, authorName));
            row.setAuthorNameLower(TextLimits.require(TICKET_REPLIES.AUTHOR_NAME_LOWER, lowerCase(authorName)));
        }
        row.setAvatar(reply.getAvatar());
        row.setContent(reply.getContent());
        row.setType(TextLimits.requireIfPresent(TICKET_REPLIES.TYPE, reply.getType()));
        row.setIsStaff(reply.isStaff());
        row.setAction(TextLimits.requireIfPresent(TICKET_REPLIES.ACTION, reply.getAction()));
        row.setAttachmentsJson(attachmentsJson(reply.getAttachments()));
        row.setCreatorIdentifier(
            TextLimits.requireIfPresent(TICKET_REPLIES.CREATOR_IDENTIFIER, reply.getCreatorIdentifier()));
        row.setCreatedAt(reply.getCreated() == null ? now : reply.getCreated());
        return row;
    }

    TicketNotesRecord note(String ticketId, TicketNote note, Instant now) {
        TicketNotesRecord row = new TicketNotesRecord();
        row.setTicketId(TextLimits.require(TICKET_NOTES.TICKET_ID, ticketId));
        row.setBody(note.getText());
        row.setIssuerName(TextLimits.requireIfPresent(TICKET_NOTES.ISSUER_NAME, note.getIssuerName()));
        row.setIssuerAvatar(note.getIssuerAvatar());
        row.setAttachmentsJson(attachmentsJson(note.getAttachments()));
        row.setCreatedAt(note.getDate() == null ? now : note.getDate());
        return row;
    }

    Map<Field<?>, Object> changes(TicketHeaderPatch patch) {
        Map<Field<?>, Object> changes = new LinkedHashMap<>();
        changes.put(TICKETS.UPDATED_AT, patch.updatedAt());
        putIfPresent(changes, TICKETS.STATUS, TextLimits.requireIfPresent(TICKETS.STATUS, idOf(patch.status())));
        putIfPresent(changes, TICKETS.IS_LOCKED, patch.locked());
        putIfPresent(changes, TICKETS.IS_HIDDEN, patch.hidden());
        putIfPresent(changes, TICKETS.EMAIL_AUTH_ENABLED, patch.emailAuthEnabled());
        putIfPresent(changes, TICKETS.SUBJECT, patch.subject());
        putIfPresent(changes, TICKETS.CREATOR_NAME, TextLimits.requireIfPresent(TICKETS.CREATOR_NAME, patch.creatorName()));
        putIfPresent(changes, TICKETS.CREATOR_UUID,
            TextLimits.requireIfPresent(TICKETS.CREATOR_UUID, UuidUtils.normalizeIfPresent(patch.creatorUuid())));
        putIfPresent(changes, TICKETS.APPEAL_WORKFLOW_STATUS,
            TextLimits.requireIfPresent(TICKETS.APPEAL_WORKFLOW_STATUS, idOf(patch.appealWorkflowStatus())));
        putIfPresent(changes, TICKETS.FORM_DATA_JSON, json.writeIfPresent(patch.formData()));
        Map<String, Object> data = patch.data();
        if (data != null) {
            changes.put(TICKETS.DATA_JSON, json.write(data));
            changes.put(TICKETS.APPEAL_PUNISHMENT_ID, appealPunishmentId(data));
        }
        putIfPresent(changes, TICKETS.AI_ANALYSIS_JSON, analysisJson(patch.aiAnalysis()));
        return changes;
    }

    Ticket ticket(TicketsRecord row, List<String> tags, List<String> assignees, List<TicketRepliesRecord> replies,
                  List<TicketNotesRecord> notes) {
        return Ticket.builder()
            .id(row.getId())
            .type(TicketCategory.fromCanonicalId(row.getType()))
            .subject(row.getSubject())
            .status(TicketStatus.fromCanonicalId(row.getStatus()))
            .priority(TicketPriority.fromCanonicalId(row.getPriority()))
            .creatorUuid(row.getCreatorUuid())
            .creatorName(row.getCreatorName())
            .creatorAvatar(row.getCreatorAvatar())
            .reportedPlayer(row.getReportedPlayerName())
            .reportedPlayerUuid(row.getReportedPlayerUuid())
            .locked(row.getIsLocked())
            .hidden(row.getIsHidden())
            .emailAuthEnabled(row.getEmailAuthEnabled())
            .appealWorkflowStatus(workflowStatus(row.getAppealWorkflowStatus()))
            .replayUrl(row.getReplayUrl())
            .replayId(row.getReplayId())
            .publicAccessKey(row.getPublicAccessKey())
            .formData(json.read(row.getFormDataJson(), MAP))
            .data(json.read(row.getDataJson(), MAP))
            .chatMessages(json.read(row.getChatMessagesJson(), CHAT_MESSAGES))
            .aiAnalysis(analysis(row.getAiAnalysisJson()))
            .tags(new ArrayList<>(tags))
            .assignedTo(new ArrayList<>(assignees))
            .replies(new ArrayList<>(replies.stream().map(this::reply).toList()))
            .notes(new ArrayList<>(notes.stream().map(this::note).toList()))
            .created(row.getCreatedAt())
            .updatedAt(row.getUpdatedAt())
            .version(row.getVersion())
            .build();
    }

    TicketSummary summary(TicketsRecord row, List<String> tags, List<String> assignees,
                          @Nullable TicketRepliesRecord lastReply) {
        return TicketSummary.builder()
            .id(row.getId())
            .type(TicketCategory.fromCanonicalId(row.getType()))
            .subject(row.getSubject())
            .status(TicketStatus.fromCanonicalId(row.getStatus()))
            .priority(TicketPriority.fromCanonicalId(row.getPriority()))
            .creatorUuid(row.getCreatorUuid())
            .creatorName(row.getCreatorName())
            .reportedPlayer(row.getReportedPlayerName())
            .reportedPlayerUuid(row.getReportedPlayerUuid())
            .tags(List.copyOf(tags))
            .assignedTo(List.copyOf(assignees))
            .locked(row.getIsLocked())
            .hidden(row.getIsHidden())
            .appealWorkflowStatus(workflowStatus(row.getAppealWorkflowStatus()))
            .appealPunishmentId(row.getAppealPunishmentId())
            .replayId(row.getReplayId())
            .publicAccessKey(row.getPublicAccessKey())
            .replyCount(row.getReplyCount())
            .lastReply(lastReply == null ? null : reply(lastReply))
            .createdAt(row.getCreatedAt())
            .updatedAt(row.getUpdatedAt())
            .version(row.getVersion())
            .build();
    }

    TicketReply reply(TicketRepliesRecord row) {
        return TicketReply.builder()
            .id(row.getId())
            .name(row.getAuthorName())
            .avatar(row.getAvatar())
            .content(row.getContent())
            .type(row.getType())
            .staff(row.getIsStaff())
            .action(row.getAction())
            .attachments(attachments(row.getAttachmentsJson()))
            .creatorIdentifier(row.getCreatorIdentifier())
            .created(row.getCreatedAt())
            .build();
    }

    private TicketNote note(TicketNotesRecord row) {
        return TicketNote.builder()
            .text(row.getBody())
            .issuerName(row.getIssuerName())
            .issuerAvatar(row.getIssuerAvatar())
            .attachments(attachments(row.getAttachmentsJson()))
            .date(row.getCreatedAt())
            .build();
    }

    int storedCharacters(TicketReply reply) {
        return codePoints(reply.getContent()) + codePoints(attachmentsJson(reply.getAttachments()));
    }

    private static int codePoints(@Nullable String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    @Nullable
    private String attachmentsJson(@Nullable List<Object> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return null;
        }
        TicketAttachmentLimits.require(attachments, json);
        return json.write(attachments);
    }

    private List<Object> attachments(@Nullable String stored) {
        List<Object> attachments = json.read(stored, ATTACHMENTS);
        return attachments == null ? new ArrayList<>() : new ArrayList<>(attachments);
    }

    @Nullable
    private String analysisJson(@Nullable AIAnalysisResult analysis) {
        return analysis == null ? null : json.write(StoredAnalysis.of(analysis));
    }

    @Nullable
    private AIAnalysisResult analysis(@Nullable String stored) {
        StoredAnalysis analysis = json.read(stored, StoredAnalysis.class);
        return analysis == null ? null : analysis.toResult();
    }

    @Nullable
    private static String appealPunishmentId(@Nullable Map<String, Object> data) {
        return reference(TICKETS.APPEAL_PUNISHMENT_ID, AppealSubject.punishmentIdOf(data).orElse(null));
    }

    @Nullable
    private static String reference(TableField<?, String> field, @Nullable String value) {
        return value != null && TextLimits.canMatch(field, value) ? TextLimits.require(field, value) : null;
    }

    static String lowerCase(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    @Nullable
    private static String idOf(@Nullable TicketStatus status) {
        return status == null ? null : status.getId();
    }

    @Nullable
    private static String idOf(@Nullable AppealWorkflowStatus status) {
        return status == null ? null : status.getId();
    }

    @Nullable
    private static AppealWorkflowStatus workflowStatus(@Nullable String stored) {
        return stored == null ? null : AppealWorkflowStatus.fromCanonicalId(stored);
    }

    private static void putIfPresent(Map<Field<?>, Object> changes, Field<?> field, @Nullable Object value) {
        if (value != null) {
            changes.put(field, value);
        }
    }

    private record StoredAction(int punishmentTypeId, String severity) {
    }

    private record StoredAnalysis(
        String analysis,
        @Nullable StoredAction suggestedAction,
        Instant createdAt,
        String rawResponse,
        @Nullable Double confidence,
        boolean wasAppliedAutomatically,
        @Nullable String appliedBy,
        boolean dismissed
    ) {
        static StoredAnalysis of(AIAnalysisResult result) {
            AIAnalysisResult.SuggestedAction action = result.getSuggestedAction();
            return new StoredAnalysis(
                result.getAnalysis(),
                action == null ? null : new StoredAction(action.getPunishmentTypeId(), action.getSeverity()),
                result.getCreatedAt(),
                result.getRawResponse(),
                result.getConfidence(),
                result.isWasAppliedAutomatically(),
                result.getAppliedBy(),
                result.isDismissed());
        }

        AIAnalysisResult toResult() {
            AIAnalysisResult result = new AIAnalysisResult(analysis,
                suggestedAction == null
                    ? null
                    : new AIAnalysisResult.SuggestedAction(suggestedAction.punishmentTypeId(), suggestedAction.severity()),
                createdAt, rawResponse);
            result.setConfidence(confidence);
            result.setWasAppliedAutomatically(wasAppliedAutomatically);
            result.setAppliedBy(appliedBy);
            result.setDismissed(dismissed);
            return result;
        }
    }
}
