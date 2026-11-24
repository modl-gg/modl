package gg.modl.backend.player.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.player.dto.response.SimplePunishmentView;
import gg.modl.backend.player.dto.response.SyncPunishmentEntry;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.repository.TicketRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SyncStaffEventServiceTest {
    private static final Instant LAST_SYNC = Instant.parse("2026-11-20T10:00:00Z");

    private final Server server = TestServers.server("server", "admin@example.com");
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final PunishmentRepository punishments = mock(PunishmentRepository.class);
    private final PublicUrls urls = mock(PublicUrls.class);
    private SyncStaffEventService service;

    @BeforeEach
    void setUp() {
        when(urls.staffTicket(any())).thenAnswer(invocation -> "https://panel.example.com/panel/tickets/" + invocation.getArgument(0));
        when(tickets.findCreatedAfterExcludingUnfinished(any(), anyInt())).thenReturn(List.of());
        when(punishments.findRecent(any(), anyInt())).thenReturn(List.of());
        StaffNotices notices = new StaffNotices(Clock.systemUTC(), urls, mock(PlayerStatusCalculator.class));
        service = new SyncStaffEventService(tickets, punishments, mock(IssuerNameResolver.class), notices);
    }

    @Test
    void aReportCarriesTheSameNoticeAsTheRealtimePush() {
        Ticket report = ticket("CHAT-1", TicketCategory.CHAT).reportedPlayer("Steve").build();
        when(tickets.findCreatedAfterExcludingUnfinished(any(), anyInt())).thenReturn(List.of(report));
        when(tickets.firstReplies(List.of("CHAT-1"))).thenReturn(Map.of("CHAT-1",
            TicketReply.builder().id("r1").content("**spam** in chat").created(LAST_SYNC).build()));

        Map<String, Object> notice = onlyNotice(List.of());

        assertThat(notice).containsEntry("id", "ticket_CHAT-1")
            .containsEntry("type", StaffNoticeType.TICKET_CREATED.name())
            .containsEntry("message", "Alex: reported Steve (Chat)");
        assertThat(notice.get("data")).isEqualTo(Map.of(
            "ticketId", "CHAT-1",
            "creatorName", "Alex",
            "subject", "Chat Report",
            "firstReplyContent", "spam in chat",
            "ticketUrl", "https://panel.example.com/panel/tickets/CHAT-1",
            "ticketType", "chat",
            "reportedPlayer", "Steve",
            "category", "chat"));
    }

    @Test
    void aStaffApplicationIsNotAnnouncedInGame() {
        when(tickets.findCreatedAfterExcludingUnfinished(any(), anyInt()))
            .thenReturn(List.of(ticket("STAFF-1", TicketCategory.APPLICATION).build()));

        assertThat(service.collectStaffEvents(server, LAST_SYNC, types(), List.of())).isEmpty();
    }

    @Test
    void onlyPardonsSinceTheLastSyncAreAnnounced() {
        SyncPunishmentEntry pardonedBefore = pardoned("OLD", LAST_SYNC.minusSeconds(60));
        SyncPunishmentEntry pardonedSince = pardoned("NEW", LAST_SYNC.plusSeconds(60));

        Map<String, Object> notice = onlyNotice(List.of(pardonedBefore, pardonedSince));

        assertThat(notice).containsEntry("id", "pardon_NEW")
            .containsEntry("message", "Admin: pardoned Steve's Manual Ban");
    }

    private Map<String, Object> onlyNotice(List<SyncPunishmentEntry> modified) {
        List<Map<String, Object>> notices = service.collectStaffEvents(server, LAST_SYNC, types(), modified);
        assertThat(notices).hasSize(1);
        return notices.getFirst();
    }

    private static Ticket.TicketBuilder ticket(String id, TicketCategory category) {
        return Ticket.builder().id(id).type(category).status(TicketStatus.OPEN).subject(category.getDisplayName())
            .creatorName("Alex").created(LAST_SYNC.plusSeconds(1)).updatedAt(LAST_SYNC.plusSeconds(1));
    }

    private static SyncPunishmentEntry pardoned(String id, Instant at) {
        SimplePunishmentView view = new SimplePunishmentView(id, "Manual Ban", "BAN", 2, 2, true, null, "reason",
            "Mod", LAST_SYNC.minusSeconds(600).toEpochMilli(), null,
            List.of(new SimplePunishmentView.Modification("MANUAL_PARDON", at.toEpochMilli(), 0L, "Admin")), false);
        return new SyncPunishmentEntry("11111111-2222-3333-4444-555555555555", "Steve", view);
    }

    private static PunishmentTypeLookup types() {
        return new PunishmentTypeLookup(Map.of(), ordinal -> "Manual Ban", ordinal -> true);
    }
}
