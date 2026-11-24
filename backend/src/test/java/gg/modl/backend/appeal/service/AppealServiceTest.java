package gg.modl.backend.appeal.service;

import static gg.modl.backend.ticket.TicketHarness.NOW;
import static gg.modl.backend.ticket.TicketHarness.UPLOAD_KEY;
import static gg.modl.backend.ticket.TicketHarness.UPLOAD_URL;
import static gg.modl.backend.ticket.TicketHarness.ticket;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.appeal.dto.request.AddAppealReplyRequest;
import gg.modl.backend.appeal.dto.request.CreateAppealRequest;
import gg.modl.backend.appeal.dto.request.UpdateAppealStatusRequest;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.repository.PunishmentPatch;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.player.service.PunishmentLifecycleService;
import gg.modl.backend.player.service.PunishmentModificationAuthorizer;
import gg.modl.backend.player.service.PunishmentMutationService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.ticket.TicketHarness;
import gg.modl.backend.ticket.data.AppealWorkflowStatus;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.repository.TicketAttachmentLimits;
import gg.modl.backend.ticket.service.ContactEmailPolicy;
import gg.modl.backend.ticket.service.TicketInputLimits;
import gg.modl.backend.webhook.WebhookEvent;
import gg.modl.proto.modl.v1.SyncStaffNotification;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class AppealServiceTest {
    private static final String STAFF_EMAIL = "staff@example.com";
    private static final String PUNISHMENT = "BANAAAAA";
    private static final String PLAYER_UUID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String VICTIM_UUID = "11111111-2222-3333-4444-555555555555";

    private final Server server = TestServers.server("Demo", "admin@example.com");
    private final PunishmentRepository punishments = mock(PunishmentRepository.class);
    private final PunishmentMutationService mutations = mock(PunishmentMutationService.class);
    private final PunishmentLifecycleService lifecycle = mock(PunishmentLifecycleService.class);
    private final PunishmentModificationAuthorizer authorizer = mock(PunishmentModificationAuthorizer.class);
    private final PunishmentTypeService appealTypes = mock(PunishmentTypeService.class);

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void playerAnswersCannotShadowTheSystemDataOfAnAppeal(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            startedPunishment(false);
            Map<String, Object> hostileAnswers = new HashMap<>();
            hostileAnswers.put("punishmentId", "victim-ban");
            hostileAnswers.put("playerUuid", VICTIM_UUID);
            hostileAnswers.put("contactEmail", "attacker@example.com");
            hostileAnswers.put("creatorEmail", "attacker@example.com");
            hostileAnswers.put("whatHappened", "I was framed");

            String id = service(harness).createAppeal(server, request("player@example.com", hostileAnswers, List.of())).id();

            Ticket appeal = harness.stored(id);
            assertThat(appeal.getData())
                .containsEntry("punishmentId", PUNISHMENT)
                .containsEntry("playerUuid", PLAYER_UUID)
                .containsEntry("contactEmail", "player@example.com")
                .doesNotContainKey("creatorEmail")
                .doesNotContainKey("whatHappened");
            assertThat(appeal.getFormData()).containsEntry("whatHappened", "I was framed").containsEntry("punishmentId", "victim-ban");
            assertThat(appeal.getReplies().getFirst().getContent()).contains("I was framed");
            assertEquals(1, harness.tickets().findAppealsByPunishmentId(PUNISHMENT).size());
            assertEquals(0, harness.tickets().findAppealsByPunishmentId("victim-ban").size());
            verify(punishments, atLeastOnce()).patch(PUNISHMENT, 3, PunishmentPatch.NONE);
            verify(mutations, atLeastOnce()).linkAppealToPunishment(eq(server), eq(PLAYER_UUID), eq(PUNISHMENT), eq(id), any());
        }
    }

    @SqlContractTest
    void appealsWithoutAnEmailAreAcceptedWhenDeliveryIsOff(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            startedPunishment(false);

            String id = service(harness).createAppeal(server, request(null, Map.of(), List.of())).id();

            assertThat(harness.stored(id).getData()).doesNotContainKey("contactEmail");
        }
    }

    @SqlContractTest
    void anAppealKeepsALocalUpload(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            startedPunishment(false);

            String id = service(harness)
                .createAppeal(server, request(null, Map.of(), List.of(Map.of("url", UPLOAD_URL)))).id();

            assertThat(harness.stored(id).getReplies().getFirst().getAttachments())
                .singleElement()
                .satisfies(attachment -> assertEquals(UPLOAD_URL, ((Map<?, ?>) attachment).get("url")));
            verify(harness.storageFiles(), atLeastOnce()).markAttached(eq(Set.of(UPLOAD_KEY)));
        }
    }

    @SqlContractTest
    void appealAnswersKeepLocalUploads(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            startedPunishment(false);
            String listed = TicketHarness.OBJECTS_PREFIX + "instance/appeal/new/listed.png";

            String id = service(harness).createAppeal(server, request(null,
                Map.of("proof_file", UPLOAD_URL, "field-1", List.of(Map.of("url", listed, "fileName", "listed.png"))),
                List.of())).id();

            Ticket appeal = harness.stored(id);
            assertEquals(UPLOAD_URL, appeal.getFormData().get("proof_file"));
            assertEquals(List.of(Map.of("url", listed, "fileName", "listed.png")), appeal.getFormData().get("field-1"));
            assertEquals(UPLOAD_URL, ((Map<?, ?>) appeal.getReplies().getFirst().getAttachments().getFirst()).get("url"));
            verify(harness.storageFiles(), atLeastOnce())
                .markAttached(eq(Set.of(UPLOAD_KEY, "instance/appeal/new/listed.png")));
        }
    }

    @SqlContractTest
    void appealAttachmentsAreBounded(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            startedPunishment(false);
            List<Object> tooLarge = List.of(Map.of(
                "url", "https://cdn.example/a", "fileName", "n".repeat(TicketAttachmentLimits.MAX_ITEM_BYTES)));
            harness.insert(appeal());

            assertThrows(ValidationException.class,
                () -> service(harness).addPublicReply(server, "APPEAL-100001", "reply", tooLarge));
            assertThrows(ValidationException.class, () -> service(harness).addReply(server, "APPEAL-100001",
                new AddAppealReplyRequest("Mod", "reply", null, tooLarge)));
            assertEquals(List.of(), harness.stored("APPEAL-100001").getReplies());
        }
    }

    @SqlContractTest
    void everyPublicAppealFieldIsBounded(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            startedPunishment(false);
            harness.insert(appeal());
            String tooLong = "x".repeat(TicketInputLimits.MAX_TEXT_BYTES + 1);
            Map<String, Object> tooManyBytes = Map.of("answer", "x".repeat(TicketInputLimits.MAX_FORM_BYTES));
            Map<String, String> longLabels = Map.of("answer", "x".repeat(TicketInputLimits.MAX_FORM_BYTES));
            List<CreateAppealRequest> oversized = List.of(
                new CreateAppealRequest(PUNISHMENT, PLAYER_UUID, null, tooLong, null, null, null, null),
                new CreateAppealRequest(PUNISHMENT, PLAYER_UUID, null, "Sorry", tooLong, null, null, null),
                new CreateAppealRequest(PUNISHMENT, PLAYER_UUID, null, "Sorry", null, tooManyBytes, null, null),
                new CreateAppealRequest(PUNISHMENT, PLAYER_UUID, null, "Sorry", null, null, null, longLabels));

            for (CreateAppealRequest request : oversized) {
                assertThrows(ValidationException.class, () -> service(harness).createAppeal(server, request));
            }
            assertThrows(ValidationException.class,
                () -> service(harness).addPublicReply(server, "APPEAL-100001", tooLong, null));
            assertEquals(List.of(), harness.stored("APPEAL-100001").getReplies());
            verify(punishments, never()).patch(any(), anyInt(), any());
        }
    }

    @SqlContractTest
    void aNewAppealsAttachmentsAreBounded(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            startedPunishment(false);
            List<Object> tooLarge = List.of(Map.of(
                "url", "https://cdn.example/a", "fileName", "n".repeat(TicketAttachmentLimits.MAX_ITEM_BYTES)));

            assertThrows(ValidationException.class,
                () -> service(harness).createAppeal(server, request(null, Map.of(), tooLarge)));
            assertEquals(0, harness.tickets().findAppealsByPunishmentId(PUNISHMENT).size());
        }
    }

    @SqlContractTest
    void anUnstartedPunishmentCannotBeAppealed(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            stubPunishment(punishment(false));

            PunishmentNotStartedException rejection = assertThrows(PunishmentNotStartedException.class,
                () -> service(harness).createAppeal(server, request(null, Map.of(), List.of())));

            assertEquals("PUNISHMENT_NOT_STARTED", rejection.getMachineCode());
            assertEquals(0, harness.tickets().findAppealsByPunishmentId(PUNISHMENT).size());
        }
    }

    @SqlContractTest
    void anotherPlayersPunishmentIsNotFound(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            startedPunishment(false);

            assertThrows(ResourceNotFoundException.class, () -> service(harness).createAppeal(server,
                new CreateAppealRequest(PUNISHMENT, VICTIM_UUID, null, "reason", null, null, List.of(), null)));
        }
    }

    @SqlContractTest
    void aNewAppealIsAnnouncedOnceAfterCommit(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            startedPunishment(false);

            String id = service(harness).createAppeal(server, request(null, Map.of(), List.of())).id();

            verify(harness.webhooks()).publish(eq(WebhookEvent.NEW_TICKETS), any());
            ArgumentCaptor<List<SyncStaffNotification>> pushed = ArgumentCaptor.captor();
            verify(harness.realtime()).pushStaffNotifications(eq(server), pushed.capture());
            assertThat(pushed.getValue()).extracting(SyncStaffNotification::getId).containsExactly("ticket_" + id);
        }
    }

    @SqlContractTest
    void aSecondAppealInProgressIsAConflict(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            startedPunishment(false);
            service(harness).createAppeal(server, request(null, Map.of(), List.of()));

            assertThrows(ConflictException.class, () -> service(harness).createAppeal(server, request(null, Map.of(), List.of())));
            assertEquals(1, harness.tickets().findAppealsByPunishmentId(PUNISHMENT).size());
        }
    }

    @SqlContractTest
    void approvalPardonsTheAppealedPunishmentFromTheSystemData(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            startedPunishment(true);
            harness.insert(appeal().formData(Map.of("punishmentId", "victim-ban", "playerUuid", VICTIM_UUID)));

            service(harness).updateStatus(server, "APPEAL-100001", new UpdateAppealStatusRequest("approved", null, "Admin", null),
                STAFF_EMAIL);

            verify(mutations, atLeastOnce()).applyAppealApproval(eq(server), eq(PLAYER_UUID), eq(PUNISHMENT),
                any(), any(), eq("APPEAL-100001"));
            verify(lifecycle, atLeastOnce()).cascadePardonLinkedBans(server, PUNISHMENT);
            Ticket stored = harness.stored("APPEAL-100001");
            assertEquals(AppealWorkflowStatus.APPROVED, stored.getAppealWorkflowStatus());
            assertEquals(true, stored.isLocked());
            assertEquals(List.of("APPEAL_STATUS_APPROVED"), stored.getReplies().stream().map(TicketReply::getAction).toList());
        }
    }

    @SqlContractTest
    void approvalWithoutThePardonPermissionChangesNothing(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            startedPunishment(true);
            harness.insert(appeal());
            doThrow(new ForbiddenException("denied")).when(authorizer).requireAppealApproval(server, STAFF_EMAIL);

            for (String alias : List.of("approved", "approve", "accepted", "accept", "APPROVED")) {
                assertThrows(ForbiddenException.class, () -> service(harness).updateStatus(server, "APPEAL-100001",
                    new UpdateAppealStatusRequest(alias, null, "Helper", "Pardoned"), STAFF_EMAIL));
            }

            Ticket stored = harness.stored("APPEAL-100001");
            assertEquals(AppealWorkflowStatus.OPEN, stored.getAppealWorkflowStatus());
            assertThat(stored.getReplies()).isEmpty();
            assertThat(stored.getData()).doesNotContainKey("resolution");
            verify(mutations, never()).applyAppealApproval(any(), any(), any(), any(), any(), any());
            verify(lifecycle, never()).cascadePardonLinkedBans(any(), any());
        }
    }

    @SqlContractTest
    void rejectingNeedsNoPardonPermission(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            startedPunishment(false);
            harness.insert(appeal());

            service(harness).updateStatus(server, "APPEAL-100001",
                new UpdateAppealStatusRequest("rejected", null, "Helper", null), STAFF_EMAIL);

            assertEquals(AppealWorkflowStatus.REJECTED, harness.stored("APPEAL-100001").getAppealWorkflowStatus());
            verifyNoInteractions(authorizer);
        }
    }

    @SqlContractTest
    void approvalWithoutAltBlockingDoesNotCascade(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            startedPunishment(false);
            harness.insert(appeal());

            service(harness).updateStatus(server, "APPEAL-100001", new UpdateAppealStatusRequest("approved", null, "Admin", null),
                STAFF_EMAIL);

            verify(lifecycle, never()).cascadePardonLinkedBans(any(), any());
        }
    }

    @SqlContractTest
    void aFreeTextResolutionNeverBecomesTheAction(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(appeal());
            String resolution = "Reduced because of ".repeat(20);

            service(harness).updateStatus(server, "APPEAL-100001", new UpdateAppealStatusRequest(null, null, "Admin", resolution),
                STAFF_EMAIL);

            Ticket stored = harness.stored("APPEAL-100001");
            assertEquals(resolution, stored.getData().get("resolution"));
            assertEquals(List.of("RESOLUTION_SET"), stored.getReplies().stream().map(TicketReply::getAction).toList());
        }
    }

    private void startedPunishment(boolean altBlocking) {
        when(appealTypes.isAppealable(any(), anyInt())).thenReturn(true);
        Punishment punishment = punishment(altBlocking);
        punishment.setStarted(NOW);
        stubPunishment(punishment);
    }

    private void stubPunishment(Punishment punishment) {
        when(punishments.findById(PUNISHMENT))
            .thenReturn(Optional.of(new PunishmentRecord("player-1", PLAYER_UUID, "Player", punishment, 3)));
    }

    private static Punishment punishment(boolean altBlocking) {
        Punishment punishment = new Punishment();
        punishment.setId(PUNISHMENT);
        punishment.setIssued(NOW);
        punishment.setModifications(new ArrayList<>());
        punishment.setNotes(new ArrayList<>());
        punishment.replaceData(new HashMap<>(Map.of("altBlocking", altBlocking)));
        return punishment;
    }

    private static Ticket.TicketBuilder appeal() {
        return ticket("APPEAL-100001")
            .type(TicketCategory.APPEAL)
            .appealWorkflowStatus(AppealWorkflowStatus.OPEN)
            .data(Map.of("punishmentId", PUNISHMENT, "playerUuid", PLAYER_UUID));
    }

    private AppealService service(TicketHarness harness) {
        return new AppealService(harness.tickets(), punishments, mutations, lifecycle, authorizer, harness.inserter(), harness.announcer(),
            harness.transitions(), harness.content(), harness.messages(), harness.appender(), harness.uploads(), harness.assembler(),
            new ContactEmailPolicy(() -> false), appealTypes, harness.database().unitOfWork(), harness.database().clock());
    }

    private TicketHarness start(DialectTarget target) {
        return TicketHarness.start(target, dataDirectory, new RetryInjection());
    }

    private static CreateAppealRequest request(String email, Map<String, Object> answers, List<Object> attachments) {
        return new CreateAppealRequest(PUNISHMENT, PLAYER_UUID, email, "Please unban me", null, answers, attachments, null);
    }
}
