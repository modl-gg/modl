package gg.modl.backend.ticket.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.role.service.MinecraftIssuerAuthorizer;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.ApplicationJson;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketPriority;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.repository.ListedTicket;
import gg.modl.backend.ticket.repository.ThreadDigest;
import gg.modl.backend.ticket.repository.TicketRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class MinecraftTicketProjectionWireTest {

    private static final Path GOLDEN_DIR = Path.of("src", "test", "resources", "golden", "minecraft-ticket");


    private final TicketRepository ticketRepository = mock(TicketRepository.class);
    private final Server server = TestServers.server("Demo", "admin@example.com");
    private final MinecraftTicketService service = new MinecraftTicketService(
        ticketRepository,
        mock(TicketNotificationService.class),
        mock(TicketInserter.class),
        mock(TicketOpenedAnnouncer.class),
        new TicketPublicLinks(publicUrls()),
        mock(PublicRecordAccessService.class),
        mock(MinecraftIssuerAuthorizer.class),
        mock(UnitOfWork.class),
        Clock.systemUTC()
    );

    private static PublicUrls publicUrls() {
        PublicUrls publicUrls = mock(PublicUrls.class);
        when(publicUrls.ticket(any(), any())).thenAnswer(invocation ->
            "https://panel.example.com/ticket/" + invocation.getArgument(0) + "?key=" + invocation.getArgument(1));
        return publicUrls;
    }

    @Test
    void ticketListItemWireFormat() throws IOException {
        assertGolden("list-item-full", service.toTicketListItem(listed(fullTicket())));
        assertGolden("list-item-minimal", service.toTicketListItem(listed(minimalTicket())));
        assertGolden("list-item-null-heavy", service.toTicketListItem(listed(nullHeavyTicket())));
    }

    @Test
    void ticketDetailWireFormat() throws IOException {
        assertGolden("detail-full", service.toTicketDetail(fullTicket()));
        assertGolden("detail-minimal", service.toTicketDetail(minimalTicket()));
        assertGolden("detail-empty-collections", service.toTicketDetail(emptyCollectionsTicket()));
        assertGolden("detail-null-heavy", service.toTicketDetail(nullHeavyTicket()));
    }

    @Test
    void ticketLookupItemWireFormat() throws IOException {
        assertGolden("lookup-full", service.toTicketLookupItem(listed(fullTicket())));
        assertGolden("lookup-no-replies", service.toTicketLookupItem(listed(minimalTicket())));
        assertGolden("lookup-null-content", service.toTicketLookupItem(listed(nullReplyContentTicket())));
    }

    @Test
    void playerTicketItemWireFormat() throws IOException {
        assertGolden("player-item-full", service.toPlayerTicketItem(fullTicket()));
        assertGolden("player-item-null-heavy", service.toPlayerTicketItem(nullHeavyTicket()));
    }

    @Test
    void minecraftReportWireFormat() throws IOException {
        assertGolden("report-full", firstReport(fullTicket()));
        assertGolden("report-null-heavy", firstReport(nullHeavyTicket()));
        assertGolden("report-empty-collections", firstReport(emptyCollectionsTicket()));
    }

    private Object firstReport(Ticket ticket) {
        when(ticketRepository.findReports(any(), any(), anyInt(), anyBoolean())).thenReturn(List.of(listed(ticket)));
        return service.getMinecraftReports(server, "open", 50).get(0);
    }

    private static ListedTicket listed(Ticket ticket) {
        List<TicketReply> visible = ticket.ensureReplies();
        return new ListedTicket(ticket, new ThreadDigest(visible.size(), visible.stream().anyMatch(TicketReply::isStaff),
            visible.isEmpty() ? null : visible.getFirst()));
    }

    private static Ticket fullTicket() {
        Ticket ticket = Ticket.builder()
            .id("TICKET-FULL")
            .type(TicketCategory.PLAYER)
            .subject("Rule break")
            .status(TicketStatus.OPEN)
            .creatorUuid("11111111-2222-3333-4444-555555555555")
            .creatorName("Reporter")
            .reportedPlayer("BadPlayer")
            .reportedPlayerUuid("22222222-3333-4444-5555-666666666666")
            .priority(TicketPriority.HIGH)
            .assignedTo(new ArrayList<>(List.of("Mod", "Admin")))
            .replies(new ArrayList<>(List.of(
                userReply("reply-user", "Please review the evidence", 1_700_000_005_000L),
                staffReply("reply-staff", "Looking into it", 1_700_000_020_000L)
            )))
            .chatMessages(new ArrayList<>(List.of(
                chatMessage("hello there", 1_700_000_001_000L, "BadPlayer"),
                chatMessage("no sender line", 1_700_000_002_000L, null)
            )))
            .replayUrl("https://cdn.example/replay.modlreplay")
            .created(Instant.ofEpochMilli(1_700_000_000_000L))
            .updatedAt(Instant.ofEpochMilli(1_700_000_050_000L))
            .locked(true)
            .publicAccessKey("full-ticket-key")
            .build();
        return ticket;
    }

    private static Ticket minimalTicket() {
        Ticket ticket = Ticket.builder()
            .id("TICKET-MIN")
            .type(TicketCategory.SUPPORT)
            .subject("Need help")
            .status(TicketStatus.OPEN)
            .created(Instant.ofEpochMilli(1_700_000_000_000L))
            .build();
        ticket.setPriority(null);
        return ticket;
    }

    private static Ticket emptyCollectionsTicket() {
        Ticket ticket = Ticket.builder()
            .id("TICKET-EMPTY")
            .type(TicketCategory.CHAT)
            .subject("Empty collections")
            .status(TicketStatus.OPEN)
            .creatorUuid("33333333-4444-5555-6666-777777777777")
            .creatorName("Chatter")
            .priority(TicketPriority.NORMAL)
            .assignedTo(new ArrayList<>())
            .replies(new ArrayList<>())
            .chatMessages(new ArrayList<>())
            .created(Instant.ofEpochMilli(1_700_000_000_000L))
            .updatedAt(Instant.ofEpochMilli(1_700_000_010_000L))
            .build();
        return ticket;
    }

    private static Ticket nullHeavyTicket() {
        Ticket ticket = Ticket.builder()
            .id("TICKET-NULL")
            .build();
        ticket.setType(null);
        ticket.setStatus(null);
        ticket.setSubject(null);
        ticket.setPriority(null);
        ticket.setCreated(null);
        ticket.setUpdatedAt(null);
        ticket.setChatMessages(null);
        return ticket;
    }

    private static Ticket nullReplyContentTicket() {
        Ticket ticket = Ticket.builder()
            .id("TICKET-NULLCONTENT")
            .type(TicketCategory.BUG)
            .subject("Null first reply content")
            .status(TicketStatus.OPEN)
            .creatorUuid("44444444-5555-6666-7777-888888888888")
            .creatorName("Buggy")
            .replies(new ArrayList<>(List.of(userReply("reply-null", null, 1_700_000_005_000L))))
            .created(Instant.ofEpochMilli(1_700_000_000_000L))
            .build();
        return ticket;
    }

    private static TicketReply userReply(String id, String content, long createdAt) {
        return TicketReply.builder()
            .id(id)
            .content(content)
            .name("Reporter")
            .creatorIdentifier("11111111-2222-3333-4444-555555555555")
            .staff(false)
            .type("user")
            .created(Instant.ofEpochMilli(createdAt))
            .build();
    }

    private static TicketReply staffReply(String id, String content, long createdAt) {
        return TicketReply.builder()
            .id(id)
            .content(content)
            .name("StaffOne")
            .creatorIdentifier("staff-uuid")
            .staff(true)
            .type("reply")
            .created(Instant.ofEpochMilli(createdAt))
            .build();
    }

    private static Ticket.ChatMessage chatMessage(String content, long timestamp, String sender) {
        return Ticket.ChatMessage.builder()
            .content(content)
            .timestamp(Instant.ofEpochMilli(timestamp))
            .sender(sender)
            .build();
    }

    private void assertGolden(String name, Object value) throws IOException {
        String actual = ApplicationJson.MAPPER.writeValueAsString(value);
        Path path = GOLDEN_DIR.resolve(name + ".json");
        if (!Files.exists(path)) {
            throw new IllegalStateException("Missing golden fixture " + path
                + "; V1 wire fixtures must be committed, never generated from current code");
        }
        JsonNode expected = ApplicationJson.MAPPER.readTree(Files.readString(path));
        JsonNode produced = ApplicationJson.MAPPER.readTree(actual);
        assertEquals(expected, produced, "V1 wire drift for " + name + " (actual=" + actual + ")");
    }
}
