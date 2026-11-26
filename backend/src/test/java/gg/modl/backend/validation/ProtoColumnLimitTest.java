package gg.modl.backend.validation;

import static gg.modl.backend.database.jooq.Tables.AUDIT_LOGS;
import static gg.modl.backend.database.jooq.Tables.AUTH_CODES;
import static gg.modl.backend.database.jooq.Tables.CHAT_LOGS;
import static gg.modl.backend.database.jooq.Tables.COMMAND_LOGS;
import static gg.modl.backend.database.jooq.Tables.EVIDENCE_UPLOAD_TOKENS;
import static gg.modl.backend.database.jooq.Tables.HOMEPAGE_CARDS;
import static gg.modl.backend.database.jooq.Tables.INVITATIONS;
import static gg.modl.backend.database.jooq.Tables.KNOWLEDGEBASE_ARTICLES;
import static gg.modl.backend.database.jooq.Tables.KNOWLEDGEBASE_CATEGORIES;
import static gg.modl.backend.database.jooq.Tables.PANEL_SESSIONS;
import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_NOTES;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_EVIDENCE;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_MODIFICATIONS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_NOTES;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_TICKETS;
import static gg.modl.backend.database.jooq.Tables.STAFF;
import static gg.modl.backend.database.jooq.Tables.STAFF_ROLES;
import static gg.modl.backend.database.jooq.Tables.STAFF_ROLE_PERMISSIONS;
import static gg.modl.backend.database.jooq.Tables.TICKETS;
import static gg.modl.backend.database.jooq.Tables.TICKET_ASSIGNEES;
import static gg.modl.backend.database.jooq.Tables.TICKET_REPLIES;
import static gg.modl.backend.database.jooq.Tables.TICKET_TAGS;
import static gg.modl.backend.database.jooq.Tables.TICKET_VERIFICATIONS;
import static gg.modl.backend.database.jooq.Tables.WEBAUTHN_CREDENTIALS;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.buf.validate.FieldRules;
import com.buf.validate.StringRules;
import com.buf.validate.ValidateProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import gg.modl.backend.database.jooq.Tables;
import gg.modl.proto.modl.v1.AddPunishmentEvidenceRequest;
import gg.modl.proto.modl.v1.AddPunishmentNoteRequest;
import gg.modl.proto.modl.v1.AddReplyRequest;
import gg.modl.proto.modl.v1.AddTagRequest;
import gg.modl.proto.modl.v1.AssignMinecraftPlayerRequest;
import gg.modl.proto.modl.v1.AssignReportRequest;
import gg.modl.proto.modl.v1.BulkTicketUpdateRequest;
import gg.modl.proto.modl.v1.ChangePunishmentDurationRequest;
import gg.modl.proto.modl.v1.ChatLogEntry;
import gg.modl.proto.modl.v1.ClaimSetupRequest;
import gg.modl.proto.modl.v1.CommandLogEntry;
import gg.modl.proto.modl.v1.CreateAppealRequest;
import gg.modl.proto.modl.v1.CreateArticleRequest;
import gg.modl.proto.modl.v1.HomepageCardDraft;
import gg.modl.proto.modl.v1.CreateCategoryRequest;
import gg.modl.proto.modl.v1.CreateEvidenceRequest;
import gg.modl.proto.modl.v1.CreateEvidenceUploadTokenRequest;
import gg.modl.proto.modl.v1.CreatePlayerNoteRequest;
import gg.modl.proto.modl.v1.CreatePunishmentRequest;
import gg.modl.proto.modl.v1.CreateStaffRequest;
import gg.modl.proto.modl.v1.CreateTicketRequest;
import gg.modl.proto.modl.v1.DismissReportRequest;
import gg.modl.proto.modl.v1.InviteStaffRequest;
import gg.modl.proto.modl.v1.MinecraftClaimTicketRequest;
import gg.modl.proto.modl.v1.MinecraftCreateTicketRequest;
import gg.modl.proto.modl.v1.ModifyPunishmentTicketsRequest;
import gg.modl.proto.modl.v1.PanelAddEvidenceRequest;
import gg.modl.proto.modl.v1.PanelAddModificationRequest;
import gg.modl.proto.modl.v1.PanelAddPunishmentNoteRequest;
import gg.modl.proto.modl.v1.PanelCreatePunishmentRequest;
import gg.modl.proto.modl.v1.PanelSendEmailCodeRequest;
import gg.modl.proto.modl.v1.PanelUpdateEmailRequest;
import gg.modl.proto.modl.v1.PanelUpdateEmailWithCodeRequest;
import gg.modl.proto.modl.v1.PanelUpdateProfileRequest;
import gg.modl.proto.modl.v1.PanelVerifyEmailCodeRequest;
import gg.modl.proto.modl.v1.PardonPlayerRequest;
import gg.modl.proto.modl.v1.PardonPunishmentRequest;
import gg.modl.proto.modl.v1.PatchGeneralSettingsRequest;
import gg.modl.proto.modl.v1.RenameWebAuthnCredentialRequest;
import gg.modl.proto.modl.v1.ResolveReportRequest;
import gg.modl.proto.modl.v1.RoleRequest;
import gg.modl.proto.modl.v1.SetupOwnerAccount;
import gg.modl.proto.modl.v1.SubmitTicketFormRequest;
import gg.modl.proto.modl.v1.TicketAssignees;
import gg.modl.proto.modl.v1.TogglePunishmentOptionRequest;
import gg.modl.proto.modl.v1.UpdateArticleRequest;
import gg.modl.proto.modl.v1.UpdateCategoryRequest;
import gg.modl.proto.modl.v1.UpdateRolePermissionsRequest;
import gg.modl.proto.modl.v1.UpdateStaffRequest;
import gg.modl.proto.modl.v1.UpdateStaffRoleRequest;
import gg.modl.proto.modl.v1.UpdateTicketRequest;
import java.util.stream.Stream;
import org.jooq.Field;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ProtoColumnLimitTest {

    static Stream<ColumnLimit> persistedStringFields() {
        return Stream.of(
            punishmentFields(),
            noteFields(),
            evidenceFields(),
            modificationFields(),
            ticketFields(),
            replyFields(),
            staffFields(),
            logFields()
        ).flatMap(fields -> fields);
    }

    private static Stream<ColumnLimit> punishmentFields() {
        return Stream.of(
            limit(CreatePunishmentRequest.getDescriptor(), "issuer_name", PUNISHMENTS.ISSUER_NAME),
            limit(CreatePunishmentRequest.getDescriptor(), "issuer_id", PUNISHMENTS.ISSUER_ID),
            limit(CreatePunishmentRequest.getDescriptor(), "severity", PUNISHMENTS.SEVERITY),
            limit(CreatePunishmentRequest.getDescriptor(), "status", PUNISHMENTS.STATUS),
            limit(CreatePunishmentRequest.getDescriptor(), "attached_ticket_ids", PUNISHMENT_TICKETS.TICKET_ID),
            limit(PanelCreatePunishmentRequest.getDescriptor(), "severity", PUNISHMENTS.SEVERITY),
            limit(PanelCreatePunishmentRequest.getDescriptor(), "status", PUNISHMENTS.STATUS),
            limit(PanelCreatePunishmentRequest.getDescriptor(), "attached_ticket_ids", PUNISHMENT_TICKETS.TICKET_ID),
            limit(ModifyPunishmentTicketsRequest.getDescriptor(), "add_ticket_ids", PUNISHMENT_TICKETS.TICKET_ID)
        );
    }

    private static Stream<ColumnLimit> noteFields() {
        return Stream.of(
            limit(AddPunishmentNoteRequest.getDescriptor(), "issuer_name", PUNISHMENT_NOTES.ISSUER_NAME),
            limit(AddPunishmentNoteRequest.getDescriptor(), "issuer_id", PUNISHMENT_NOTES.ISSUER_ID),
            limit(CreatePlayerNoteRequest.getDescriptor(), "issuer_name", PLAYER_NOTES.ISSUER_NAME),
            limit(CreatePlayerNoteRequest.getDescriptor(), "issuer_id", PLAYER_NOTES.ISSUER_ID)
        );
    }

    private static Stream<ColumnLimit> evidenceFields() {
        return Stream.of(
            limit(CreateEvidenceRequest.getDescriptor(), "type", PUNISHMENT_EVIDENCE.TYPE),
            limit(CreateEvidenceRequest.getDescriptor(), "file_name", PUNISHMENT_EVIDENCE.FILE_NAME),
            limit(CreateEvidenceRequest.getDescriptor(), "file_type", PUNISHMENT_EVIDENCE.FILE_TYPE),
            limit(PanelAddEvidenceRequest.getDescriptor(), "type", PUNISHMENT_EVIDENCE.TYPE),
            limit(PanelAddEvidenceRequest.getDescriptor(), "file_name", PUNISHMENT_EVIDENCE.FILE_NAME),
            limit(PanelAddEvidenceRequest.getDescriptor(), "file_type", PUNISHMENT_EVIDENCE.FILE_TYPE),
            limit(AddPunishmentEvidenceRequest.getDescriptor(), "issuer_name", PUNISHMENT_EVIDENCE.UPLOADED_BY),
            limit(AddPunishmentEvidenceRequest.getDescriptor(), "issuer_id", PUNISHMENT_EVIDENCE.UPLOADED_BY_ID),
            limit(CreateEvidenceUploadTokenRequest.getDescriptor(), "issuer_name", EVIDENCE_UPLOAD_TOKENS.ISSUER_NAME)
        );
    }

    private static Stream<ColumnLimit> modificationFields() {
        return Stream.of(
            limit(PanelAddModificationRequest.getDescriptor(), "type", PUNISHMENT_MODIFICATIONS.TYPE),
            limit(PanelAddModificationRequest.getDescriptor(), "appeal_ticket_id", PUNISHMENT_MODIFICATIONS.APPEAL_TICKET_ID),
            limit(PardonPunishmentRequest.getDescriptor(), "issuer_name", PUNISHMENT_MODIFICATIONS.ISSUER_NAME),
            limit(PardonPunishmentRequest.getDescriptor(), "issuer_id", PUNISHMENT_MODIFICATIONS.ISSUER_ID),
            limit(PardonPlayerRequest.getDescriptor(), "issuer_name", PUNISHMENT_MODIFICATIONS.ISSUER_NAME),
            limit(PardonPlayerRequest.getDescriptor(), "issuer_id", PUNISHMENT_MODIFICATIONS.ISSUER_ID),
            limit(ChangePunishmentDurationRequest.getDescriptor(), "issuer_name", PUNISHMENT_MODIFICATIONS.ISSUER_NAME),
            limit(ChangePunishmentDurationRequest.getDescriptor(), "issuer_id", PUNISHMENT_MODIFICATIONS.ISSUER_ID),
            limit(TogglePunishmentOptionRequest.getDescriptor(), "issuer_name", PUNISHMENT_MODIFICATIONS.ISSUER_NAME),
            limit(TogglePunishmentOptionRequest.getDescriptor(), "issuer_id", PUNISHMENT_MODIFICATIONS.ISSUER_ID)
        );
    }

    private static Stream<ColumnLimit> ticketFields() {
        return Stream.of(
            limit(CreateTicketRequest.getDescriptor(), "creator_name", TICKETS.CREATOR_NAME),
            limit(CreateTicketRequest.getDescriptor(), "reported_player_name", TICKETS.REPORTED_PLAYER_NAME),
            limit(CreateTicketRequest.getDescriptor(), "tags", TICKET_TAGS.TAG),
            limit(MinecraftCreateTicketRequest.getDescriptor(), "creator_name", TICKETS.CREATOR_NAME),
            limit(MinecraftCreateTicketRequest.getDescriptor(), "reported_player_name", TICKETS.REPORTED_PLAYER_NAME),
            limit(MinecraftCreateTicketRequest.getDescriptor(), "tags", TICKET_TAGS.TAG),
            limit(UpdateTicketRequest.getDescriptor(), "tags", TICKET_TAGS.TAG),
            limit(UpdateTicketRequest.getDescriptor(), "assigned_to", TICKET_ASSIGNEES.ASSIGNEE),
            limit(TicketAssignees.getDescriptor(), "names", TICKET_ASSIGNEES.ASSIGNEE),
            limit(AddTagRequest.getDescriptor(), "tag", TICKET_TAGS.TAG),
            limit(BulkTicketUpdateRequest.getDescriptor(), "add_labels", TICKET_TAGS.TAG)
        );
    }

    private static Stream<ColumnLimit> replyFields() {
        return Stream.of(
            limit(AddReplyRequest.getDescriptor(), "action", TICKET_REPLIES.ACTION),
            limit(AddReplyRequest.getDescriptor(), "creator_identifier", TICKET_REPLIES.CREATOR_IDENTIFIER),
            limit(CreateTicketRequest.getDescriptor(), "creator_identifier", TICKET_REPLIES.CREATOR_IDENTIFIER),
            limit(SubmitTicketFormRequest.getDescriptor(), "creator_identifier", TICKET_REPLIES.CREATOR_IDENTIFIER)
        );
    }

    private static Stream<ColumnLimit> staffFields() {
        return Stream.of(
            limit(CreateStaffRequest.getDescriptor(), "email", STAFF.EMAIL),
            limit(CreateStaffRequest.getDescriptor(), "username", STAFF.USERNAME),
            limit(CreateStaffRequest.getDescriptor(), "role", STAFF.ROLE_ID),
            limit(UpdateStaffRequest.getDescriptor(), "email", STAFF.EMAIL),
            limit(UpdateStaffRequest.getDescriptor(), "role", STAFF.ROLE_ID),
            limit(UpdateStaffRoleRequest.getDescriptor(), "role", STAFF.ROLE_ID),
            limit(AssignMinecraftPlayerRequest.getDescriptor(), "minecraft_username", STAFF.ASSIGNED_MINECRAFT_USERNAME),
            limit(PanelUpdateProfileRequest.getDescriptor(), "username", STAFF.USERNAME),
            limit(PanelUpdateProfileRequest.getDescriptor(), "language", STAFF.LANGUAGE),
            limit(InviteStaffRequest.getDescriptor(), "email", INVITATIONS.EMAIL),
            limit(InviteStaffRequest.getDescriptor(), "emails", INVITATIONS.EMAIL),
            limit(InviteStaffRequest.getDescriptor(), "role", INVITATIONS.ROLE_ID),
            limit(RoleRequest.getDescriptor(), "name", STAFF_ROLES.NAME),
            limit(RoleRequest.getDescriptor(), "permissions", STAFF_ROLE_PERMISSIONS.PERMISSION),
            limit(UpdateRolePermissionsRequest.getDescriptor(), "permissions", STAFF_ROLE_PERMISSIONS.PERMISSION)
        );
    }

    private static Stream<ColumnLimit> logFields() {
        return Stream.of(
            limit(ChatLogEntry.getDescriptor(), "username", CHAT_LOGS.USERNAME),
            limit(ChatLogEntry.getDescriptor(), "server", CHAT_LOGS.SERVER_NAME),
            limit(CommandLogEntry.getDescriptor(), "username", COMMAND_LOGS.USERNAME),
            limit(CommandLogEntry.getDescriptor(), "server", COMMAND_LOGS.SERVER_NAME),
            limit(RoleRequest.getDescriptor(), "name", STAFF_ROLES.NAME_LOWER),
            limit(BulkTicketUpdateRequest.getDescriptor(), "assign_to", TICKET_ASSIGNEES.ASSIGNEE),
            limit(AssignReportRequest.getDescriptor(), "assignee", TICKET_ASSIGNEES.ASSIGNEE),
            limit(DismissReportRequest.getDescriptor(), "dismissed_by", TICKET_REPLIES.AUTHOR_NAME),
            limit(ResolveReportRequest.getDescriptor(), "resolved_by", TICKET_REPLIES.AUTHOR_NAME),
            limit(CreateTicketRequest.getDescriptor(), "creator_name", TICKET_REPLIES.AUTHOR_NAME),
            limit(CreateTicketRequest.getDescriptor(), "creator_email", TICKET_VERIFICATIONS.EMAIL),
            limit(MinecraftCreateTicketRequest.getDescriptor(), "creator_name", TICKET_REPLIES.AUTHOR_NAME),
            limit(MinecraftClaimTicketRequest.getDescriptor(), "player_name", TICKETS.CREATOR_NAME),
            limit(MinecraftClaimTicketRequest.getDescriptor(), "player_name", TICKET_REPLIES.AUTHOR_NAME),
            limit(CreateAppealRequest.getDescriptor(), "email", TICKET_VERIFICATIONS.EMAIL),
            limit(CreatePunishmentRequest.getDescriptor(), "issuer_name", PUNISHMENTS.ISSUER_NAME_LOWER),
            limit(CreatePunishmentRequest.getDescriptor(), "issuer_name", PUNISHMENT_NOTES.ISSUER_NAME),
            limit(CreatePunishmentRequest.getDescriptor(), "issuer_id", PUNISHMENT_NOTES.ISSUER_ID),
            limit(CreatePunishmentRequest.getDescriptor(), "issuer_name", TICKET_REPLIES.AUTHOR_NAME),
            limit(CreatePunishmentRequest.getDescriptor(), "issuer_name", AUDIT_LOGS.SOURCE),
            limit(PardonPlayerRequest.getDescriptor(), "issuer_name", PUNISHMENT_NOTES.ISSUER_NAME),
            limit(PardonPlayerRequest.getDescriptor(), "issuer_id", PUNISHMENT_NOTES.ISSUER_ID),
            limit(PardonPunishmentRequest.getDescriptor(), "issuer_name", PUNISHMENT_NOTES.ISSUER_NAME),
            limit(PardonPunishmentRequest.getDescriptor(), "issuer_id", PUNISHMENT_NOTES.ISSUER_ID),
            limit(ChangePunishmentDurationRequest.getDescriptor(), "issuer_name", PUNISHMENT_NOTES.ISSUER_NAME),
            limit(ChangePunishmentDurationRequest.getDescriptor(), "issuer_id", PUNISHMENT_NOTES.ISSUER_ID),
            limit(TogglePunishmentOptionRequest.getDescriptor(), "issuer_name", PUNISHMENT_NOTES.ISSUER_NAME),
            limit(TogglePunishmentOptionRequest.getDescriptor(), "issuer_id", PUNISHMENT_NOTES.ISSUER_ID),
            limit(AddPunishmentEvidenceRequest.getDescriptor(), "issuer_name", PUNISHMENT_NOTES.ISSUER_NAME),
            limit(AddPunishmentEvidenceRequest.getDescriptor(), "issuer_id", PUNISHMENT_NOTES.ISSUER_ID),
            limit(CreateEvidenceUploadTokenRequest.getDescriptor(), "issuer_name", PUNISHMENT_EVIDENCE.UPLOADED_BY),
            limit(CreateEvidenceUploadTokenRequest.getDescriptor(), "issuer_name", PUNISHMENT_NOTES.ISSUER_NAME),
            limit(ModifyPunishmentTicketsRequest.getDescriptor(), "issuer_name", TICKET_REPLIES.AUTHOR_NAME),
            limit(ChatLogEntry.getDescriptor(), "username", PLAYERS.USERNAME),
            limit(CommandLogEntry.getDescriptor(), "username", PLAYERS.USERNAME),
            limit(PanelSendEmailCodeRequest.getDescriptor(), "email", AUTH_CODES.EMAIL),
            limit(PanelVerifyEmailCodeRequest.getDescriptor(), "email", AUTH_CODES.EMAIL),
            limit(PanelVerifyEmailCodeRequest.getDescriptor(), "email", PANEL_SESSIONS.EMAIL),
            limit(PanelUpdateEmailRequest.getDescriptor(), "new_email", AUTH_CODES.EMAIL),
            limit(PanelUpdateEmailWithCodeRequest.getDescriptor(), "new_email", AUTH_CODES.EMAIL),
            limit(PanelUpdateEmailWithCodeRequest.getDescriptor(), "new_email", STAFF.EMAIL),
            limit(PanelUpdateEmailWithCodeRequest.getDescriptor(), "new_email", Tables.INSTANCE.ADMIN_EMAIL),
            limit(RenameWebAuthnCredentialRequest.getDescriptor(), "name", WEBAUTHN_CREDENTIALS.NAME),
            limit(SetupOwnerAccount.getDescriptor(), "email", Tables.INSTANCE.ADMIN_EMAIL),
            limit(ClaimSetupRequest.getDescriptor(), "public_url", Tables.INSTANCE.PUBLIC_URL),
            limit(PatchGeneralSettingsRequest.getDescriptor(), "public_url", Tables.INSTANCE.PUBLIC_URL),
            limit(CreateCategoryRequest.getDescriptor(), "name", KNOWLEDGEBASE_CATEGORIES.NAME),
            limit(UpdateCategoryRequest.getDescriptor(), "name", KNOWLEDGEBASE_CATEGORIES.NAME),
            limit(CreateArticleRequest.getDescriptor(), "title", KNOWLEDGEBASE_ARTICLES.TITLE),
            limit(UpdateArticleRequest.getDescriptor(), "title", KNOWLEDGEBASE_ARTICLES.TITLE),
            limit(HomepageCardDraft.getDescriptor(), "id", HOMEPAGE_CARDS.ID),
            limit(HomepageCardDraft.getDescriptor(), "title", HOMEPAGE_CARDS.TITLE),
            limit(HomepageCardDraft.getDescriptor(), "icon", HOMEPAGE_CARDS.ICON),
            limit(HomepageCardDraft.getDescriptor(), "category_id", HOMEPAGE_CARDS.CATEGORY_ID)
        );
    }

    private static ColumnLimit limit(Descriptor message, String fieldName, Field<String> column) {
        return new ColumnLimit(message, fieldName, column);
    }

    @ParameterizedTest
    @MethodSource("persistedStringFields")
    void theProtoMaxLengthFitsTheColumn(ColumnLimit limit) {
        FieldDescriptor field = limit.message().findFieldByName(limit.fieldName());
        assertNotNull(field, () -> limit + " names a field the message does not declare");

        StringRules rules = stringRulesOf(field);
        assertTrue(rules.hasMaxLen(), () -> limit + " declares no buf.validate max_len");

        int columnLength = limit.column().getDataType().length();
        assertTrue(columnLength > 0, () -> limit + " maps to a column without a length");
        assertTrue(rules.getMaxLen() <= columnLength,
            () -> limit + " allows " + rules.getMaxLen() + " characters but the column holds " + columnLength);
    }

    private static StringRules stringRulesOf(FieldDescriptor field) {
        FieldRules rules = field.getOptions().getExtension(ValidateProto.field);
        return field.isRepeated() ? rules.getRepeated().getItems().getString() : rules.getString();
    }

    record ColumnLimit(Descriptor message, String fieldName, Field<String> column) {
        @Override
        public String toString() {
            return message.getName() + "." + fieldName + " -> " + column.getQualifiedName();
        }
    }
}
