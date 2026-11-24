package gg.modl.backend.ticket.service;

import static gg.modl.backend.ticket.TicketHarness.UPLOAD_KEY;
import static gg.modl.backend.ticket.TicketHarness.UPLOAD_URL;
import static gg.modl.backend.ticket.TicketHarness.ticket;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.player.service.PunishmentModificationAuthorizer;
import gg.modl.backend.player.service.Shortenings;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.QuickResponseSettings;
import gg.modl.backend.settings.data.TicketFormSettings;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.ticket.TicketHarness;
import gg.modl.backend.ticket.data.AppealWorkflowStatus;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketPriority;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.dto.request.AddNoteRequest;
import gg.modl.backend.ticket.dto.request.AddReplyRequest;
import gg.modl.backend.ticket.dto.request.BulkTicketUpdateRequest;
import gg.modl.backend.ticket.dto.request.CreateTicketRequest;
import gg.modl.backend.ticket.dto.request.QuickResponseRequest;
import gg.modl.backend.ticket.dto.request.SubmitTicketFormRequest;
import gg.modl.backend.ticket.dto.request.UpdateTicketRequest;
import gg.modl.backend.ticket.dto.response.QuickResponseResult;
import gg.modl.backend.ticket.repository.TicketAttachmentLimits;
import gg.modl.backend.webhook.WebhookEvent;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.io.TempDir;

class TicketServiceTest {
    private final Server server = TestServers.server("server", "admin@example.com");

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void createTicketRejectsAnInvalidCreatorEmail(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            ValidationException rejection = assertThrows(ValidationException.class,
                () -> harness.ticketService().createTicket(server, create("support", "Help", "not-an-email", null, null)));

            assertEquals("A valid email address is required", rejection.getMessage());
            assertEquals(0, harness.tickets().findMinecraftTickets(null, null, 10).size());
        }
    }

    @SqlContractTest
    void createTicketStoresTheTicketOnceAndAnnouncesItOnce(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            String id = harness.ticketService()
                .createTicket(server, create("support", "Help", " Player@Example.com ", null, null)).id();

            Ticket stored = harness.stored(id);
            assertTrue(id.matches("SUPPORT-\\d{6}"));
            assertEquals("player@example.com", stored.getData().get("creatorEmail"));
            assertEquals(TicketStatus.OPEN, stored.getStatus());
            assertEquals(1, stored.getReplies().size());
            assertEquals(1, harness.tickets().findMinecraftTickets(null, null, 10).size());
            verify(harness.webhooks(), times(1)).publish(eq(WebhookEvent.NEW_TICKETS), any());
        }
    }

    @SqlContractTest
    void createTicketAcceptsLegacyTypeSpacingAndPriorityAliases(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            String id = harness.ticketService().createTicket(server, new CreateTicketRequest("staff application", "",
                "Legacy alias submit", null, "Applicant", null, null, null, null, null, null, List.of("legacy"), "medium",
                null, null, null)).id();

            Ticket stored = harness.stored(id);
            assertEquals(TicketCategory.APPLICATION, stored.getType());
            assertEquals(TicketPriority.NORMAL, stored.getPriority());
            assertEquals(TicketStatus.UNFINISHED, stored.getStatus());
        }
    }

    @SqlContractTest
    void createTicketKeepsLocalUploadsFromAttachmentsAndFormFields(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            String formUpload = TicketHarness.OBJECTS_PREFIX + "instance/tickets/new/log.txt";

            String id = harness.ticketService().createTicket(server, create("support", "Help", null,
                Map.of("file_upload", formUpload), List.of(Map.of("url", UPLOAD_URL)))).id();

            assertEquals(List.of(UPLOAD_URL, formUpload), attachedUrls(harness.stored(id)));
            assertEquals(formUpload, harness.stored(id).getFormData().get("file_upload"));
            verify(harness.storageFiles(), atLeastOnce())
                .markAttached(eq(Set.of(UPLOAD_KEY, "instance/tickets/new/log.txt")));
        }
    }

    @SqlContractTest
    void aSweptUploadIsNotShownAsAttached(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            doReturn(0).when(harness.storageFiles()).markAttached(any());
            doReturn(List.of()).when(harness.storageFiles()).findByKeys(any());

            String id = harness.ticketService().createTicket(server, create("support", "Help", null,
                Map.of("file_upload", UPLOAD_URL, "details", "kept"), List.of(Map.of("url", UPLOAD_URL)))).id();

            Ticket stored = harness.stored(id);
            assertEquals(List.of(), attachedUrls(stored));
            assertEquals(Map.of("details", "kept"), stored.getFormData());
        }
    }

    @SqlContractTest
    void submittedFormKeepsLocalUploads(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("SUPPORT-2").status(TicketStatus.UNFINISHED));

            harness.ticketService().submitTicketForm(server, "SUPPORT-2", new SubmitTicketFormRequest(
                "Subject", null, Map.of(), List.of(Map.of("url", UPLOAD_URL)), null, null, null), false);

            assertEquals(List.of(UPLOAD_URL), attachedUrls(harness.stored("SUPPORT-2")));
            verify(harness.storageFiles(), atLeastOnce()).markAttached(eq(Set.of(UPLOAD_KEY)));
        }
    }

    @SqlContractTest
    void submittedFormFieldsNeverShadowTheSystemDataOfATicket(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("SUPPORT-3").status(TicketStatus.UNFINISHED)
                .data(Map.of("creatorIdentifier", "creator-1")));

            harness.ticketService().submitTicketForm(server, "SUPPORT-3", new SubmitTicketFormRequest("Appeal", null,
                Map.of("punishmentId", "victim-ban", "playerUuid", "victim-uuid", "contactEmail", "attacker@example.com"),
                List.of(), null, null, null), false);

            Ticket stored = harness.stored("SUPPORT-3");
            assertEquals(Map.of("creatorIdentifier", "creator-1"), stored.getData());
            assertEquals("victim-ban", stored.getFormData().get("punishmentId"));
            assertTrue(stored.appealSubject().isEmpty());
            assertEquals(List.of(), harness.tickets().findAppealsByPunishmentId("victim-ban"));
        }
    }

    @SqlContractTest
    void anAppealCannotBeRewrittenThroughTheTicketForm(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("APPEAL-3").type(TicketCategory.APPEAL).status(TicketStatus.UNFINISHED));

            assertThrows(ConflictException.class, () -> harness.ticketService().submitTicketForm(server, "APPEAL-3",
                new SubmitTicketFormRequest("Appeal for Punishment: victim-ban", null, Map.of(), List.of(), null, null, null), false));
            assertEquals("Need help", harness.stored("APPEAL-3").getSubject());
        }
    }

    @SqlContractTest
    void submitTicketFormPromotesUnfinishedTicketAndAddsOneInitialReply(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("SUPPORT-2").status(TicketStatus.UNFINISHED).creatorName("PlayerOne"));

            harness.ticketService().submitTicketForm(server, "SUPPORT-2", new SubmitTicketFormRequest("Updated subject",
                "player@example.com", Map.of("issue_type", "Bug report", "emailAuthEnabled", true), List.of(),
                "creator-1", null, null), true);

            Ticket stored = harness.stored("SUPPORT-2");
            assertEquals(TicketStatus.OPEN, stored.getStatus());
            assertEquals("Updated subject", stored.getSubject());
            assertTrue(stored.isEmailAuthEnabled());
            assertEquals(true, stored.getData().get("emailAuthEnabled"));
            assertEquals(true, stored.getFormData().get("emailAuthEnabled"));
            assertEquals("player@example.com", stored.getData().get("creatorEmail"));
            assertEquals("creator-1", stored.getData().get("creatorIdentifier"));
            assertEquals(1, stored.getReplies().size());
            assertTrue(stored.getReplies().getFirst().getContent().contains("Issue Type"));
        }
    }

    @SqlContractTest
    void theOptInFieldEnablesEmailAuthWithoutAnyFormAnswers(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("SUPPORT-2").status(TicketStatus.UNFINISHED));

            harness.ticketService().submitTicketForm(server, "SUPPORT-2", new SubmitTicketFormRequest(
                "Subject", "player@example.com", null, List.of(), null, null, true), false);

            assertTrue(harness.stored("SUPPORT-2").isEmailAuthEnabled());
            assertEquals("player@example.com", harness.stored("SUPPORT-2").getData().get("creatorEmail"));
        }
    }

    @SqlContractTest
    void aFormThatRequiresEmailAuthProtectsTicketsWithoutAnswers(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("SUPPORT-2").status(TicketStatus.UNFINISHED));
            when(harness.forms().getFormByType(server, "support"))
                .thenReturn(TicketFormSettings.TicketForm.builder().requireEmailAuth(true).build());

            harness.ticketService().submitTicketForm(server, "SUPPORT-2", new SubmitTicketFormRequest(
                "Subject", "player@example.com", null, List.of(), null, null, null), false);

            assertTrue(harness.stored("SUPPORT-2").isEmailAuthEnabled());
        }
    }

    @SqlContractTest
    void submitTicketFormCannotDisableExistingEmailAuth(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("SUPPORT-3").status(TicketStatus.UNFINISHED).emailAuthEnabled(true));

            harness.ticketService().submitTicketForm(server, "SUPPORT-3", new SubmitTicketFormRequest("Updated subject",
                "player@example.com", Map.of("emailAuthEnabled", false), List.of(), null, null, false), false);

            Ticket stored = harness.stored("SUPPORT-3");
            assertTrue(stored.isEmailAuthEnabled());
            assertEquals("player@example.com", stored.getData().get("creatorEmail"));
            assertEquals(false, stored.getFormData().get("emailAuthEnabled"));
        }
    }

    @SqlContractTest
    void aLinkHolderCannotProtectAnOpenTicketWithTheirOwnEmail(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("SUPPORT-4"));

            assertThrows(ConflictException.class, () -> harness.ticketService().submitTicketForm(server, "SUPPORT-4",
                new SubmitTicketFormRequest("Subject", "attacker@example.com", null, List.of(), null, null, true), false));

            Ticket stored = harness.stored("SUPPORT-4");
            assertFalse(stored.isEmailAuthEnabled());
            assertNull(stored.getData());
        }
    }

    @SqlContractTest
    void aClosedTicketCannotBeResubmitted(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("SUPPORT-5").status(TicketStatus.CLOSED).locked(true));

            assertThrows(ConflictException.class, () -> harness.ticketService().submitTicketForm(server, "SUPPORT-5",
                new SubmitTicketFormRequest("Subject", null, null, List.of(), null, null, null), false));
            assertEquals("Need help", harness.stored("SUPPORT-5").getSubject());
        }
    }

    @SqlContractTest
    void aLinkHolderCannotTakeOverTheCreatorIdentifierOfAnOpenTicket(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("SUPPORT-6").data(Map.of("creatorIdentifier", "creator-1")));

            assertThrows(ConflictException.class, () -> harness.ticketService().submitTicketForm(server, "SUPPORT-6",
                new SubmitTicketFormRequest("Subject", null, null, List.of(), "attacker", null, null), false));

            assertEquals("creator-1", harness.stored("SUPPORT-6").getData().get("creatorIdentifier"));
        }
    }

    @SqlContractTest
    void updateDataCannotChangeProtectedKeys(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            Map<String, Object> system = Map.of(
                "creatorEmail", "player@example.com",
                "contactEmail", "appeal@example.com",
                "creatorIdentifier", "creator-1",
                "emailAuthEnabled", true,
                "resolution", "Reduced",
                "punishmentId", "BANAAAAA",
                "playerUuid", "0f8fad5b-d9cb-469f-a165-70867728950e");
            harness.insert(ticket("APPEAL-1").type(TicketCategory.APPEAL).data(system), ticket("SUPPORT-1"));

            for (String key : system.keySet()) {
                assertThrows(ValidationException.class, () -> harness.ticketService()
                    .updateTicket(server, "APPEAL-1", dataUpdate(Map.of(key, "attacker")), "mod@example.com"), key);
                assertThrows(ValidationException.class, () -> harness.ticketService()
                    .updateTicket(server, "APPEAL-1", dataUpdate(Collections.singletonMap(key, null)), "mod@example.com"), key);
                assertThrows(ValidationException.class, () -> harness.ticketService()
                    .updateTicket(server, "SUPPORT-1", dataUpdate(Map.of(key, "attacker")), "mod@example.com"), key);
            }

            assertEquals(system, harness.stored("APPEAL-1").getData());
            assertEquals(1, harness.tickets().findAppealsByPunishmentId("BANAAAAA").size());
            assertNull(harness.stored("SUPPORT-1").getData());
        }
    }

    @SqlContractTest
    void updateDataAcceptsOtherKeysAndUnchangedProtectedOnes(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("SUPPORT-1").data(Map.of("creatorEmail", "player@example.com")));

            harness.ticketService().updateTicket(server, "SUPPORT-1",
                dataUpdate(Map.of("creatorEmail", "player@example.com", "handledIn", "voice")), "mod@example.com");

            assertEquals(Map.of("creatorEmail", "player@example.com", "handledIn", "voice"),
                harness.stored("SUPPORT-1").getData());
        }
    }

    @SqlContractTest
    void openingAndUpdateReplyAttachmentsAreBounded(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("SUPPORT-1").status(TicketStatus.UNFINISHED));
            List<Object> tooLarge = List.of(Map.of(
                "url", "https://cdn.example/a", "fileName", "n".repeat(TicketAttachmentLimits.MAX_ITEM_BYTES)));

            assertThrows(ValidationException.class,
                () -> harness.ticketService().createTicket(server, create("support", "Help", null, null, tooLarge)));
            assertThrows(ValidationException.class, () -> harness.ticketService().submitTicketForm(server, "SUPPORT-1",
                new SubmitTicketFormRequest("Subject", null, null, tooLarge, null, null, null), false));
            assertThrows(ValidationException.class, () -> harness.ticketService().updateTicket(server, "SUPPORT-1",
                update(null, new AddReplyRequest("Mod", "reply", tooLarge, null), null, null),
                "mod@example.com"));

            assertEquals(TicketStatus.UNFINISHED, harness.stored("SUPPORT-1").getStatus());
            assertEquals(List.of(), harness.stored("SUPPORT-1").getReplies());
        }
    }

    @SqlContractTest
    void publicFormsAreBoundedWithoutBeanValidation(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("SUPPORT-1").status(TicketStatus.UNFINISHED));
            TicketService service = harness.ticketService();
            Map<String, Object> tooManyBytes = Map.of("log", "x".repeat(TicketInputLimits.MAX_FORM_BYTES));
            Map<String, Object> tooManyFields = IntStream.rangeClosed(0, TicketInputLimits.MAX_FORM_ENTRIES).boxed()
                .collect(Collectors.toMap(index -> "field" + index, index -> "x"));
            String longSubject = "x".repeat(TicketInputLimits.MAX_LINE_BYTES + 1);

            for (Map<String, Object> form : List.of(tooManyBytes, tooManyFields)) {
                assertThrows(ValidationException.class,
                    () -> service.createTicket(server, create("support", "Help", null, form, null)));
                assertThrows(ValidationException.class,
                    () -> service.createUnfinishedTicket(server, create("support", "Help", null, form, null)));
                assertThrows(ValidationException.class,
                    () -> service.submitTicketForm(server, "SUPPORT-1", submission("Help", form), false));
            }
            assertThrows(ValidationException.class,
                () -> service.createTicket(server, create("support", longSubject, null, null, null)));
            assertThrows(ValidationException.class,
                () -> service.submitTicketForm(server, "SUPPORT-1", submission(longSubject, null), false));
            assertEquals(TicketStatus.UNFINISHED, harness.stored("SUPPORT-1").getStatus());
        }
    }

    @SqlContractTest
    void aSubmittedTicketCannotBeResubmittedToGrowItsForm(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            String mostOfTheCap = "x".repeat(TicketInputLimits.MAX_FORM_BYTES * 2 / 3);
            harness.insert(ticket("SUPPORT-1").status(TicketStatus.UNFINISHED));
            TicketService service = harness.ticketService();

            service.submitTicketForm(server, "SUPPORT-1", submission("Help", Map.of("first", mostOfTheCap)), false);

            assertThrows(ConflictException.class,
                () -> service.submitTicketForm(server, "SUPPORT-1", submission("Help", Map.of("second", mostOfTheCap)), false));
            assertEquals(Set.of("first"), harness.stored("SUPPORT-1").getFormData().keySet());
        }
    }

    @SqlContractTest
    void aLargeRealisticFormIsStillAccepted(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            Map<String, Object> form = IntStream.range(0, 40).boxed()
                .collect(Collectors.toMap(index -> "field" + index, index -> "answer ".repeat(1_400)));

            String id = harness.ticketService().createTicket(server, create("support", "Help", null, form, null)).id();

            assertEquals(40, harness.stored(id).getFormData().size());
        }
    }

    private static SubmitTicketFormRequest submission(String subject, Map<String, Object> form) {
        return new SubmitTicketFormRequest(subject, null, form, null, null, null, null);
    }

    @SqlContractTest
    void theLastAssigneeCanBeCleared(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("A-1").assignedTo(List.of("amy")));

            harness.ticketService().updateTicket(server, "A-1", update(null, null, null, List.of()), "mod@example.com");

            assertEquals(List.of(), harness.stored("A-1").getAssignedTo());
            assertEquals(0, harness.stored("A-1").getVersion());
        }
    }

    @SqlContractTest
    void updateTicketStoresReplyNoteAndCloseInOneUnit(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("A-1"));
            AddReplyRequest reply = new AddReplyRequest("Mod", "done", List.of(Map.of("url", UPLOAD_URL)), "Close");
            AddNoteRequest note = new AddNoteRequest("see file", List.of(Map.of("url", UPLOAD_URL, "fileName", "shot.png")));

            harness.ticketService().updateTicket(server, "A-1", update("closed", reply, note, null), "mod@example.com");

            Ticket stored = harness.stored("A-1");
            assertEquals(TicketStatus.CLOSED, stored.getStatus());
            assertTrue(stored.isLocked());
            assertEquals(1, stored.getReplies().size());
            assertEquals(1, stored.getNotes().size());
            assertEquals(UPLOAD_URL, ((Map<?, ?>) stored.getNotes().getFirst().getAttachments().getFirst()).get("url"));
            assertEquals(List.of(UPLOAD_URL), attachedUrls(stored));
            verify(harness.notifications(), times(1)).notifyTicketReply(eq(server), any(), any());
            verify(harness.notifications(), times(1)).notifyTicketClosed(eq(server), any());
        }
    }

    @SqlContractTest
    void replacingTagsKeepsOnlyTheRequestedOnes(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("A-1").tags(List.of("old", "kept")));

            harness.ticketService().updateTicket(server, "A-1", new UpdateTicketRequest(
                null, null, null, null, List.of("kept", "new"), null, null, null, "Mod"), "mod@example.com");

            assertEquals(List.of("kept", "new"), harness.stored("A-1").getTags());
        }
    }

    @SqlContractTest
    void bulkUpdateAppliesEveryChangeAndNotifiesOnlyNewlyClosed(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("A-1").tags(List.of("old")), ticket("A-2").status(TicketStatus.CLOSED).locked(true));

            int updated = harness.ticketService().bulkUpdateTickets(server, new BulkTicketUpdateRequest(
                List.of("A-2", "A-1", "A-404"), true, List.of("new"), List.of("old"), "Amy, Bob", null), "mod@example.com");

            assertEquals(2, updated);
            Ticket closed = harness.stored("A-1");
            assertEquals(TicketStatus.CLOSED, closed.getStatus());
            assertEquals(List.of("new"), closed.getTags());
            assertEquals(List.of("amy", "bob"), closed.getAssignedTo());
            verify(harness.notifications(), times(1)).notifyTicketClosed(eq(server), any());
        }
    }

    @SqlContractTest
    void processQuickResponseRepliesAndCloses(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("SUPPORT-1"));
            QuickResponseSettings settings = QuickResponseSettings.builder().build();
            QuickResponseSettings.Action action = QuickResponseSettings.Action.builder()
                .id("close").name("Close").message("Resolved").closeTicket(true).appealAction("none").build();
            when(harness.quickResponses().getQuickResponseSettings(server)).thenReturn(settings);
            when(harness.quickResponses().findAction(settings, "general", "close")).thenReturn(action);

            QuickResponseResult result = harness.ticketService().processQuickResponse(server, "SUPPORT-1",
                new QuickResponseRequest("close", "general", null, null, null, null), "mod@example.com", "Moderator");

            assertTrue(result.success());
            Ticket stored = harness.stored("SUPPORT-1");
            assertEquals(TicketStatus.CLOSED, stored.getStatus());
            assertTrue(stored.isLocked());
            assertEquals("Moderator", stored.getReplies().getFirst().getName());
            verify(harness.notifications(), times(1)).notifyTicketReply(eq(server), any(), any());
            verify(harness.notifications(), times(1)).notifyTicketClosed(eq(server), any());
        }
    }

    @SqlContractTest
    void aClosingQuickResponseNeedsTheClosePermissionAndChangesNothingWithoutIt(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("APPEAL-1").type(TicketCategory.APPEAL));
            QuickResponseSettings settings = QuickResponseSettings.builder().build();
            QuickResponseSettings.Action action = QuickResponseSettings.Action.builder()
                .id("close").name("Close").message("Denied").closeTicket(true).appealAction("reject").build();
            when(harness.quickResponses().getQuickResponseSettings(server)).thenReturn(settings);
            when(harness.quickResponses().findAction(settings, "appeal", "close")).thenReturn(action);
            TicketChangeAuthorizer authorizer = mock(TicketChangeAuthorizer.class);
            doThrow(new ForbiddenException("denied"))
                .when(authorizer).requirePermitted(server, "helper@example.com", Set.of(TicketChange.STATUS));

            assertThrows(ForbiddenException.class, () -> harness.ticketService(authorizer).processQuickResponse(server, "APPEAL-1",
                new QuickResponseRequest("close", "appeal", null, null, null, null), "helper@example.com", "Helper"));

            Ticket stored = harness.stored("APPEAL-1");
            assertEquals(TicketStatus.OPEN, stored.getStatus());
            assertFalse(stored.isLocked());
            assertTrue(stored.getReplies().isEmpty());
        }
    }

    @SqlContractTest
    void aPardoningQuickResponseNeedsThePardonPermissionAndChangesNothingWithoutIt(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("APPEAL-1").type(TicketCategory.APPEAL));
            quickResponse(harness, "pardon");
            PunishmentModificationAuthorizer pardon = mock(PunishmentModificationAuthorizer.class);
            doThrow(new ForbiddenException("denied")).when(pardon).requireAppealApproval(server, "helper@example.com");
            TicketService service = harness.ticketService(mock(TicketChangeAuthorizer.class), pardon);

            assertThrows(ForbiddenException.class, () -> service.processQuickResponse(server, "APPEAL-1",
                new QuickResponseRequest("decide", "appeal", null, null, null, null), "helper@example.com", "Helper"));

            Ticket stored = harness.stored("APPEAL-1");
            assertEquals(TicketStatus.OPEN, stored.getStatus());
            assertTrue(stored.getReplies().isEmpty());

            assertTrue(service.processQuickResponse(server, "APPEAL-1",
                new QuickResponseRequest("decide", "appeal", null, null, null, null), "mod@example.com", "Mod").success());
            verify(pardon).requireAppealApproval(server, "mod@example.com");
        }
    }

    @SqlContractTest
    void aReducingQuickResponseIsAShorteningAndChangesNothingWithoutBothPermissions(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("APPEAL-1").type(TicketCategory.APPEAL));
            quickResponse(harness, "reduce");
            PunishmentModificationAuthorizer authorizer = mock(PunishmentModificationAuthorizer.class);
            when(authorizer.shorteningBy(server, "helper@example.com")).thenReturn(Shortenings.REFUSED);
            when(authorizer.shorteningBy(server, "mod@example.com")).thenReturn(Shortenings.PERMITTED);
            TicketService service = harness.ticketService(mock(TicketChangeAuthorizer.class), authorizer);

            assertThrows(ForbiddenException.class, () -> service.processQuickResponse(server, "APPEAL-1",
                new QuickResponseRequest("decide", "appeal", null, null, null, null), "helper@example.com", "Helper"));
            assertTrue(harness.stored("APPEAL-1").getReplies().isEmpty());

            assertTrue(service.processQuickResponse(server, "APPEAL-1",
                new QuickResponseRequest("decide", "appeal", null, null, null, null), "mod@example.com", "Mod").success());
        }
    }

    @SqlContractTest
    void aRejectingQuickResponseNeedsNoPardonPermission(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("APPEAL-1").type(TicketCategory.APPEAL));
            quickResponse(harness, "reject");
            PunishmentModificationAuthorizer pardon = mock(PunishmentModificationAuthorizer.class);

            assertTrue(harness.ticketService(mock(TicketChangeAuthorizer.class), pardon).processQuickResponse(server, "APPEAL-1",
                new QuickResponseRequest("decide", "appeal", null, null, null, null), "helper@example.com", "Helper").success());

            verifyNoInteractions(pardon);
        }
    }

    @SqlContractTest
    void patchAndBulkClosingNeverApproveAnAppeal(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("APPEAL-1").type(TicketCategory.APPEAL).appealWorkflowStatus(AppealWorkflowStatus.OPEN),
                ticket("APPEAL-2").type(TicketCategory.APPEAL).appealWorkflowStatus(AppealWorkflowStatus.OPEN));

            harness.ticketService().updateTicket(server, "APPEAL-1", update("closed", null, null, null), "helper@example.com");
            harness.ticketService().bulkUpdateTickets(server,
                new BulkTicketUpdateRequest(List.of("APPEAL-2"), true, null, null, null, null), "helper@example.com");

            assertEquals(AppealWorkflowStatus.CLOSED, harness.stored("APPEAL-1").getAppealWorkflowStatus());
            assertEquals(AppealWorkflowStatus.CLOSED, harness.stored("APPEAL-2").getAppealWorkflowStatus());
        }
    }

    private void quickResponse(TicketHarness harness, String appealAction) {
        QuickResponseSettings settings = QuickResponseSettings.builder().build();
        QuickResponseSettings.Action action = QuickResponseSettings.Action.builder()
            .id("decide").name("Decide").message("Decided").closeTicket(true).appealAction(appealAction).build();
        when(harness.quickResponses().getQuickResponseSettings(server)).thenReturn(settings);
        when(harness.quickResponses().findAction(settings, "appeal", "decide")).thenReturn(action);
    }

    @SqlContractTest
    void everyPanelStatusChangeAsksTheChangeAuthorizer(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("SUPPORT-1"));
            TicketChangeAuthorizer authorizer = mock(TicketChangeAuthorizer.class);
            TicketService service = harness.ticketService(authorizer);

            service.updateTicket(server, "SUPPORT-1", update("closed", null, null, null), "mod@example.com");
            service.bulkUpdateTickets(server,
                new BulkTicketUpdateRequest(List.of("SUPPORT-1"), false, null, null, null, true), "mod@example.com");

            verify(authorizer).requirePermitted(server, "mod@example.com", Set.of(TicketChange.STATUS));
            verify(authorizer).requirePermitted(server, "mod@example.com", Set.of(TicketChange.STATUS, TicketChange.VISIBILITY));
        }
    }

    @SqlContractTest
    void closingForAPunishmentIsIdempotent(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("PLAYER-1").type(TicketCategory.PLAYER));

            harness.ticketService().closeTicketForPunishment(server, "PLAYER-1", "Mod");
            harness.ticketService().closeTicketForPunishment(server, "PLAYER-1", "Mod");
            harness.ticketService().closeTicketForPunishment(server, "PLAYER-404", "Mod");

            assertEquals(TicketStatus.CLOSED, harness.stored("PLAYER-1").getStatus());
            assertEquals(1, harness.stored("PLAYER-1").getReplies().size());
            verify(harness.notifications(), never()).notifyTicketClosed(any(), any());
        }
    }

    private TicketHarness start(DialectTarget target) {
        return TicketHarness.start(target, dataDirectory, new RetryInjection());
    }

    private static CreateTicketRequest create(String type, String subject, String email, Map<String, Object> formData,
                                              List<Object> attachments) {
        return new CreateTicketRequest(type, subject, "body", null, "Player", email, null, null, null, formData,
            attachments, null, null, null, null, null);
    }

    private static UpdateTicketRequest update(String status, AddReplyRequest reply, AddNoteRequest note, List<String> assignees) {
        return new UpdateTicketRequest(status, null, reply, note, null, null, null, assignees, "Mod");
    }

    private static UpdateTicketRequest dataUpdate(Map<String, Object> data) {
        return new UpdateTicketRequest(null, null, null, null, null, data, null, null, "Mod");
    }

    private static List<Object> attachedUrls(Ticket ticket) {
        return ticket.getReplies().stream()
            .flatMap(reply -> reply.getAttachments().stream())
            .<Object>map(attachment -> ((Map<?, ?>) attachment).get("url"))
            .toList();
    }
}
