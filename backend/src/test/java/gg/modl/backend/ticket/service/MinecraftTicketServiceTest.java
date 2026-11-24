package gg.modl.backend.ticket.service;

import static gg.modl.backend.ticket.TicketHarness.ACCESS_KEY;
import static gg.modl.backend.ticket.TicketHarness.reply;
import static gg.modl.backend.ticket.TicketHarness.ticket;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.role.service.MinecraftIssuer;
import gg.modl.backend.role.service.MinecraftIssuerAuthorizer;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.ticket.TicketHarness;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketNote;
import gg.modl.backend.ticket.data.TicketPriority;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.repository.ListedTicket;
import gg.modl.backend.ticket.dto.request.AssignReportRequest;
import gg.modl.backend.ticket.dto.request.MinecraftClaimTicketRequest;
import gg.modl.backend.ticket.dto.request.MinecraftCreateTicketRequest;
import gg.modl.backend.ticket.service.MinecraftTicketService.MinecraftTicketClaimStatus;
import gg.modl.backend.ticket.service.MinecraftTicketService.ReportOperationStatus;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;

class MinecraftTicketServiceTest {
    private static final String CLAIMER = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String OTHER = "11111111-2222-3333-4444-555555555555";
    private static final MinecraftIssuer CONSOLE = MinecraftIssuer.of(null, true);

    private final Server server = TestServers.server("server", "admin@example.com");

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void createMinecraftTicketMapsPluginTypeAndPersistsReply(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            Ticket created = harness.minecraftService().createMinecraftTicket(server, new MinecraftCreateTicketRequest(
                CLAIMER.toUpperCase(Locale.ROOT), "PlayerOne", "chat", "Chat report", "reported bad chat", OTHER, "PlayerTwo",
                List.of("hello world"), List.of("report"), null, "survival", null));

            Ticket stored = harness.stored(created.getId());
            assertEquals(TicketCategory.CHAT, stored.getType());
            assertEquals(TicketPriority.NORMAL, stored.getPriority());
            assertEquals(CLAIMER, stored.getCreatorUuid());
            assertEquals("reported bad chat", stored.getReplies().getFirst().getContent());
            assertEquals(1, stored.getReplies().size());
            assertEquals(1, stored.getChatMessages().size());
            assertEquals("survival", stored.getData().get("createdServer"));
        }
    }

    @SqlContractTest
    void theTicketViewsOfTheGameLeaveStaffNotesOut(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("SUPPORT-1")
                .notes(List.of(TicketNote.builder().text("informant is Alex").issuerName("Mod").date(TicketHarness.NOW).build()))
                .replies(List.of(reply("2").content("my question").build())));
            MinecraftTicketService service = harness.minecraftService();
            Ticket stored = harness.stored("SUPPORT-1");
            ListedTicket listed = harness.tickets().findListedByIds(List.of("SUPPORT-1")).getFirst();

            assertEquals(1, service.toTicketDetail(stored).replies().size());
            assertEquals("my question", service.toTicketDetail(stored).replies().getFirst().content());
            assertEquals("my question", service.toTicketLookupItem(listed).firstReplyContent());
            assertFalse(service.toTicketListItem(listed).hasStaffResponse());
            assertEquals(1, service.toTicketListItem(listed).replyCount());
        }
    }

    @SqlContractTest
    void claimRenamesMatchingNonStaffReplies(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("SUPPORT-1").creatorName("Old Web User").replies(List.of(
                reply("1").name("Old Web User").build(),
                reply("2").name("Staff").staff(true).build())));

            var result = harness.minecraftService().claimMinecraftTicket(server, "SUPPORT-1",
                new MinecraftClaimTicketRequest(CLAIMER, "VerifiedPlayer", ACCESS_KEY));

            assertEquals(MinecraftTicketClaimStatus.SUCCESS, result.status());
            Ticket stored = harness.stored("SUPPORT-1");
            assertEquals(CLAIMER, stored.getCreatorUuid());
            assertEquals("VerifiedPlayer", stored.getCreatorName());
            assertEquals("VerifiedPlayer", stored.getReplies().getFirst().getName());
            assertEquals(CLAIMER, stored.getReplies().getFirst().getCreatorIdentifier());
            assertEquals("Staff", stored.getReplies().getLast().getName());
        }
    }

    @SqlContractTest
    void claimingAnUnlinkedTicketRequiresItsAccessKey(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("SUPPORT-1"));

            assertEquals(MinecraftTicketClaimStatus.NOT_FOUND, claim(harness, CLAIMER, null));
            assertEquals(MinecraftTicketClaimStatus.NOT_FOUND, claim(harness, CLAIMER, "not-the-key"));
            assertNull(harness.stored("SUPPORT-1").getCreatorUuid());
        }
    }

    @SqlContractTest
    void claimingALinkedTicketDoesNotRevealItWithoutTheKey(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("SUPPORT-1").creatorUuid(CLAIMER));

            assertEquals(MinecraftTicketClaimStatus.SUCCESS, claim(harness, CLAIMER, null));
            assertEquals(MinecraftTicketClaimStatus.NOT_FOUND, claim(harness, OTHER, null));
            assertEquals(MinecraftTicketClaimStatus.ALREADY_LINKED, claim(harness, OTHER, ACCESS_KEY));
            assertEquals(CLAIMER, harness.stored("SUPPORT-1").getCreatorUuid());
        }
    }

    @SqlContractTest
    void anEmailProtectedTicketCannotBeClaimedWithTheLinkAlone(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("SUPPORT-1").emailAuthEnabled(true).data(Map.of("creatorEmail", "player@example.com")));

            assertEquals(MinecraftTicketClaimStatus.NOT_FOUND, claim(harness, CLAIMER, ACCESS_KEY));

            when(harness.verificationService().validateToken(server, "SUPPORT-1", "verified")).thenReturn(true);
            assertEquals(MinecraftTicketClaimStatus.SUCCESS, claim(harness, CLAIMER, "verified"));
        }
    }

    @SqlContractTest
    void dismissClosesTheReportWithOneReply(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("PLAYER-1").type(TicketCategory.PLAYER));

            var result = harness.minecraftService().dismissMinecraftReport(
                server, "PLAYER-1", CONSOLE, "Moderator", "Insufficient evidence");

            assertEquals(ReportOperationStatus.SUCCESS, result.status());
            Ticket stored = harness.stored("PLAYER-1");
            assertEquals(TicketStatus.CLOSED, stored.getStatus());
            assertEquals(1, stored.getReplies().size());
            assertEquals("Moderator", stored.getReplies().getFirst().getName());
            assertEquals("Insufficient evidence", stored.getData().get("dismissReason"));
            assertEquals(TicketHarness.NOW.toEpochMilli(), ((Number) stored.getData().get("dismissedAt")).longValue());
            verify(harness.notifications(), times(1)).notifyTicketReply(eq(server), any(), any());
            verify(harness.notifications(), times(1)).notifyTicketClosed(eq(server), any());
        }
    }

    @SqlContractTest
    void resolveAndDismissRefuseAppealsAndOtherNonReportTickets(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("APPEAL-1").type(TicketCategory.APPEAL), ticket("SUPPORT-1"), ticket("BUG-1").type(TicketCategory.BUG));
            MinecraftTicketService service = harness.minecraftService();

            for (String id : List.of("APPEAL-1", "SUPPORT-1", "BUG-1")) {
                assertEquals(ReportOperationStatus.NOT_FOUND, service.dismissMinecraftReport(server, id, CONSOLE, "Moderator", null).status(), id);
                assertEquals(ReportOperationStatus.NOT_FOUND,
                    service.resolveMinecraftReport(server, id, CONSOLE, "Moderator", "Punished", "PUNISH01").status(), id);
                Ticket stored = harness.stored(id);
                assertEquals(TicketStatus.OPEN, stored.getStatus(), id);
                assertTrue(stored.getReplies().isEmpty(), id);
            }
            verifyNoInteractions(harness.notifications());
        }
    }

    @SqlContractTest
    void aStaffMemberWithoutTheClosePermissionChangesNoReport(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("PLAYER-1").type(TicketCategory.PLAYER));
            MinecraftIssuer helper = MinecraftIssuer.of("staff-1", false);
            MinecraftIssuerAuthorizer refusing = mock(MinecraftIssuerAuthorizer.class);
            doThrow(new ForbiddenException("denied")).when(refusing)
                .requirePermission(server, helper, PermissionService.TICKET_CLOSE_ALL);
            MinecraftTicketService service = harness.minecraftService(refusing);

            assertThrows(ForbiddenException.class, () -> service.dismissMinecraftReport(server, "PLAYER-1", helper, "Helper", null));
            assertThrows(ForbiddenException.class,
                () -> service.resolveMinecraftReport(server, "PLAYER-1", helper, "Helper", "Punished", "PUNISH01"));
            assertEquals(TicketStatus.OPEN, harness.stored("PLAYER-1").getStatus());
        }
    }

    @SqlContractTest
    void assignmentRefusesAppealsAndOtherNonReportTickets(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("APPEAL-1").type(TicketCategory.APPEAL), ticket("SUPPORT-1"), ticket("BUG-1").type(TicketCategory.BUG));
            MinecraftTicketService service = harness.minecraftService();

            for (String id : List.of("APPEAL-1", "SUPPORT-1", "BUG-1")) {
                assertEquals(ReportOperationStatus.NOT_FOUND,
                    service.assignMinecraftReport(server, id, new AssignReportRequest("Moderator")).status(), id);
                assertEquals(List.of(), harness.stored(id).getAssignedTo(), id);
            }
        }
    }

    @SqlContractTest
    void aChatReportCanBeResolved(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("CHAT-1").type(TicketCategory.CHAT));

            assertEquals(ReportOperationStatus.SUCCESS,
                harness.minecraftService().resolveMinecraftReport(server, "CHAT-1", CONSOLE, "Moderator", "Punished", "PUNISH01").status());
            assertEquals(TicketStatus.CLOSED, harness.stored("CHAT-1").getStatus());
        }
    }

    @SqlContractTest
    void assignmentSplitsNormalisesAndClears(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("PLAYER-1").type(TicketCategory.PLAYER));
            MinecraftTicketService service = harness.minecraftService();

            service.assignMinecraftReport(server, "PLAYER-1", new AssignReportRequest(" Bob,amy, BOB "));
            assertEquals(List.of("amy", "bob"), harness.stored("PLAYER-1").getAssignedTo());

            service.assignMinecraftReport(server, "PLAYER-1", new AssignReportRequest("none"));
            assertEquals(List.of(), harness.stored("PLAYER-1").getAssignedTo());
        }
    }

    @SqlContractTest
    void listsAcceptUppercaseUuids(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(
                ticket("PLAYER-1").type(TicketCategory.PLAYER).reportedPlayerUuid(CLAIMER),
                ticket("SUPPORT-1").creatorUuid(CLAIMER));
            MinecraftTicketService service = harness.minecraftService();

            assertEquals(1, service.getMinecraftTicketsByCreator(server, CLAIMER.toUpperCase(Locale.ROOT), 25).size());
            assertEquals(1, service.getMinecraftReportsForPlayer(server, CLAIMER.toUpperCase(Locale.ROOT), "open", 10).size());
        }
    }

    private MinecraftTicketClaimStatus claim(TicketHarness harness, String uuid, String key) {
        return harness.minecraftService().claimMinecraftTicket(
            server, "SUPPORT-1", new MinecraftClaimTicketRequest(uuid, "Player", key)).status();
    }

    private TicketHarness start(DialectTarget target) {
        return TicketHarness.start(target, dataDirectory, new RetryInjection());
    }
}
