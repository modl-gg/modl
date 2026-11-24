package gg.modl.backend.ticket.service;

import static gg.modl.backend.ticket.TicketHarness.UPLOAD_KEY;
import static gg.modl.backend.ticket.TicketHarness.UPLOAD_URL;
import static gg.modl.backend.ticket.TicketHarness.ticket;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.ticket.TicketHarness;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.dto.request.AddNoteRequest;
import gg.modl.backend.ticket.dto.request.AddReplyRequest;
import gg.modl.backend.ticket.repository.TicketAttachmentLimits;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.io.TempDir;

class TicketReplyServiceTest {
    private final Server server = TestServers.server("server", "admin@example.com");

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aPublicReplyKeepsALocalUpload(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("A-1").creatorName("Player"));

            harness.replyService().addPublicReply(server, "A-1", "here", List.of(UPLOAD_URL));

            assertEquals(1, harness.stored("A-1").getReplies().size());
            assertEquals(UPLOAD_URL,
                ((Map<?, ?>) harness.stored("A-1").getReplies().getFirst().getAttachments().getFirst()).get("url"));
            verify(harness.storageFiles(), atLeastOnce()).markAttached(eq(Set.of(UPLOAD_KEY)));
        }
    }

    @SqlContractTest
    void aNoteKeepsALocalUpload(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("A-1"));

            harness.replyService().addNote(server, "A-1",
                new AddNoteRequest("see file", List.of(Map.of("url", UPLOAD_URL))), "Mod", "mod@example.com");

            assertEquals(1, harness.stored("A-1").getNotes().size());
            assertEquals(UPLOAD_URL,
                ((Map<?, ?>) harness.stored("A-1").getNotes().getFirst().getAttachments().getFirst()).get("url"));
            verify(harness.storageFiles(), atLeastOnce()).markAttached(eq(Set.of(UPLOAD_KEY)));
        }
    }

    @SqlContractTest
    void anInternalNoteNeedsTheNotePermissionAndNothingIsWrittenWithoutIt(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("A-1"));
            TicketChangeAuthorizer authorizer = mock(TicketChangeAuthorizer.class);
            doThrow(new ForbiddenException("denied"))
                .when(authorizer).requirePermitted(server, "helper@example.com", EnumSet.of(TicketChange.NOTE));
            TicketReplyService service = harness.replyService(authorizer);

            assertThrows(ForbiddenException.class,
                () -> service.addNote(server, "A-1", new AddNoteRequest("internal", null), "Helper", "helper@example.com"));
            assertEquals(List.of(), harness.stored("A-1").getNotes());

            service.addNote(server, "A-1", new AddNoteRequest("internal", null), "Mod", "mod@example.com");
            assertEquals(1, harness.stored("A-1").getNotes().size());
        }
    }

    @SqlContractTest
    void noteAndReplyAttachmentsAreBoundedPerItemAndInTotal(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("A-1"));
            TicketReplyService service = harness.replyService();
            List<Object> oneTooLarge = List.of(attachment(0, TicketAttachmentLimits.MAX_ITEM_BYTES));
            List<Object> tooManyBytes = IntStream.range(0, 10)
                .<Object>mapToObj(index -> attachment(index, TicketAttachmentLimits.MAX_TOTAL_BYTES / 9))
                .toList();

            for (List<Object> attachments : List.of(oneTooLarge, tooManyBytes)) {
                assertThrows(ValidationException.class,
                    () -> service.addNote(server, "A-1", new AddNoteRequest("note", attachments), "Mod", "mod@example.com"));
                assertThrows(ValidationException.class,
                    () -> service.addPublicReply(server, "A-1", "reply", attachments));
                assertThrows(ValidationException.class, () -> service.addReply(server, "A-1",
                    new AddReplyRequest("Mod", "reply", attachments, null)));
            }
            assertEquals(List.of(), harness.stored("A-1").getNotes());
            assertEquals(List.of(), harness.stored("A-1").getReplies());
        }
    }

    @SqlContractTest
    void aPublicReplyIsBoundedInCharactersAndALongPastedLogStillFits(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("A-1"));
            TicketReplyService service = harness.replyService();
            String largest = "€".repeat(TicketInputLimits.MAX_PLAYER_REPLY_CHARACTERS);
            String tooManyBytes = "x".repeat(TicketInputLimits.MAX_PLAYER_REPLY_CHARACTERS + 1);
            List<Object> tooManyFiles = IntStream.rangeClosed(0, TicketInputLimits.MAX_ATTACHMENTS)
                .<Object>mapToObj(index -> "https://cdn.example/" + index)
                .toList();

            assertThrows(ValidationException.class, () -> service.addPublicReply(server, "A-1", tooManyBytes, null));
            assertThrows(ValidationException.class, () -> service.addPublicReply(server, "A-1", "files", tooManyFiles));
            service.addPublicReply(server, "A-1", largest, null);

            assertEquals(1, harness.stored("A-1").getReplies().size());
            assertEquals(largest, harness.stored("A-1").getReplies().getFirst().getContent());
        }
    }

    @SqlContractTest
    void aLockedTicketTakesNoReply(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("A-1").status(TicketStatus.CLOSED).locked(true));

            assertThrows(ConflictException.class, () -> harness.replyService().addPublicReply(server, "A-1", "late", null));
            assertEquals(List.of(), harness.stored("A-1").getReplies());
        }
    }

    @SqlContractTest
    void aMissingTicketIsNotFound(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            assertThrows(ResourceNotFoundException.class, () -> harness.replyService().addPublicReply(server, "A-404", "x", null));
            assertThrows(ResourceNotFoundException.class, () -> harness.replyService().addTag(server, "A-404", "bug"));
        }
    }

    @SqlContractTest
    void aStaffReplyNotifiesOnce(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("A-1"));

            harness.replyService().addReply(server, "A-1", new AddReplyRequest("Mod", "hello", null, null));

            assertEquals(1, harness.stored("A-1").getReplies().size());
            verify(harness.notifications(), times(1)).notifyTicketReply(eq(server), any(), any());
        }
    }

    @SqlContractTest
    void aStaffReplyIsAlwaysStoredAsAPublicStaffReplyWhateverTheClientSent(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("A-1"));

            harness.replyService().addReply(server, "A-1", new AddReplyRequest("Mod", "hello", null, null));

            TicketReply stored = harness.stored("A-1").getReplies().getFirst();
            assertEquals("staff", stored.getType());
            assertTrue(stored.isStaff());
        }
    }

    @SqlContractTest
    void tagChangesReturnTheStoredTags(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("A-1").tags(List.of("bug")));

            assertEquals(List.of("bug", "vip"), harness.replyService().addTag(server, "A-1", "vip"));
            assertEquals(List.of("vip"), harness.replyService().removeTag(server, "A-1", "bug"));
        }
    }

    private static Map<String, Object> attachment(int index, int padding) {
        return Map.of("url", "https://cdn.example/" + index, "fileName", "n".repeat(padding));
    }

    private TicketHarness start(DialectTarget target) {
        return TicketHarness.start(target, dataDirectory, new RetryInjection());
    }
}
