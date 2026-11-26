package gg.modl.backend.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.dashboard.dto.response.ActivityItemResponse;
import gg.modl.backend.dashboard.dto.response.DashboardMetricsResponse;
import gg.modl.backend.dashboard.dto.response.MinecraftDashboardStatsResponse;
import gg.modl.backend.dashboard.dto.response.RecentPunishmentResponse;
import gg.modl.backend.dashboard.repository.DashboardRepository;
import gg.modl.backend.dashboard.repository.StaffIdentity;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentPhase;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.player.service.IssuerNameResolver;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.staff.service.StaffService;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.repository.TicketRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DashboardServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-15T00:00:00Z");
    private static final Instant WEEK_AGO = NOW.minus(Duration.ofDays(7));

    private final Server server = TestServers.server("Demo", "admin@example.com");
    private final DashboardRepository dashboard = mock(DashboardRepository.class);
    private final PunishmentRepository punishments = mock(PunishmentRepository.class);
    private final StaffRepository staffRepository = mock(StaffRepository.class);
    private final StaffService staffService = mock(StaffService.class);
    private final PunishmentTypeService types = mock(PunishmentTypeService.class);
    private final IssuerNameResolver issuers = mock(IssuerNameResolver.class);
    private final DashboardService service = new DashboardService(dashboard, mock(TicketRepository.class), punishments,
        staffRepository, staffService, types, issuers, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void minecraftStatsCountByStoredCategorySoDeletedTypesStillCount() {
        when(punishments.countByCategory(PunishmentPhase.ACTIVE, NOW)).thenReturn(Map.of(
            ResolvedCategory.BAN, 3L, ResolvedCategory.MUTE, 2L, ResolvedCategory.NONE, 1L));
        when(dashboard.countUnresolvedReports()).thenReturn(4L);
        when(dashboard.countUnresolvedTickets()).thenReturn(5L);
        when(dashboard.countOnlinePlayers()).thenReturn(12L);
        when(dashboard.countPlayers()).thenReturn(50L);
        when(staffRepository.findAssignedMinecraftUuids()).thenReturn(List.of("uuid-1", "uuid-2"));
        when(dashboard.countOnlineAmong(List.of("uuid-1", "uuid-2"))).thenReturn(2L);

        assertThat(service.getMinecraftStats(server))
            .isEqualTo(new MinecraftDashboardStatsResponse(4, 5, 2, 12, 3, 2, 6, 50));
        verifyNoInteractions(types);
    }

    @Test
    void metricsCompareTheWindowWithTheOneBeforeIt() {
        Instant twoWeeksAgo = NOW.minus(Duration.ofDays(14));
        when(dashboard.countPlayersFirstJoinedAfter(WEEK_AGO)).thenReturn(15L);
        when(dashboard.countPlayersFirstJoinedBetween(twoWeeksAgo, WEEK_AGO)).thenReturn(10L);
        when(dashboard.countTicketsCreatedAfter(WEEK_AGO)).thenReturn(5L);
        when(dashboard.countTicketsCreatedBetween(twoWeeksAgo, WEEK_AGO)).thenReturn(10L);
        when(dashboard.countPunishments()).thenReturn(77L);
        when(punishments.count(PunishmentPhase.ACTIVE, NOW)).thenReturn(2L);

        DashboardMetricsResponse metrics = service.getMetrics(server, "7d");

        assertThat(metrics.totalPunishments()).isEqualTo(77);
        assertThat(metrics.activePunishments()).isEqualTo(2);
        assertThat(metrics.playersTrend()).isEqualTo(50);
        assertThat(metrics.ticketsTrend()).isEqualTo(-50);
    }

    @Test
    void recentPunishmentsResolveTheIssuerAndTheDeletedTypeNameAndAreActiveOnlyOnceStarted() {
        Punishment punishment = new Punishment();
        punishment.setId("BAN00001");
        punishment.setTypeOrdinal(18);
        punishment.setIssuerId("k7m2qz9xv4b8c1d3");
        punishment.setIssued(NOW);
        punishment.setStarted(NOW);
        Punishment waiting = new Punishment();
        waiting.setId("BAN00002");
        waiting.setTypeOrdinal(18);
        waiting.setIssued(NOW);
        when(punishments.findRecent(WEEK_AGO, 10)).thenReturn(List.of(
            new PunishmentRecord("internal", "uuid", "Target", punishment, 0),
            new PunishmentRecord("internal", "uuid", "Target", waiting, 0)));
        when(issuers.resolveForPunishments(eq(server), argThat(listed -> listed.stream()
            .anyMatch(candidate -> "k7m2qz9xv4b8c1d3".equals(candidate.getIssuerId())))))
            .thenReturn(Map.of("k7m2qz9xv4b8c1d3", "Alice"));
        when(types.getPunishmentTypeName(server, 18)).thenReturn("Cheating (deleted)");

        List<RecentPunishmentResponse> recent = service.getRecentPunishments(server, 10);
        assertThat(recent).first().satisfies(started -> {
            assertThat(started.type()).isEqualTo("Cheating (deleted)");
            assertThat(started.issuerName()).isEqualTo("Alice");
            assertThat(started.playerUuid()).isEqualTo("uuid");
            assertThat(started.active()).isTrue();
        });
        assertThat(recent).extracting(RecentPunishmentResponse::active).containsExactly(true, false);
    }

    @Test
    void recentActivityIsNewestFirstAndLimited() {
        Staff staff = Staff.builder().id("staff-1").username("Alice").build();
        Ticket ticket = Ticket.builder()
            .id("SUPPORT-1")
            .type(TicketCategory.SUPPORT)
            .creatorName("Alice")
            .created(NOW.minusSeconds(60))
            .replies(List.of(TicketReply.builder().name("Bob").created(NOW.minusSeconds(1)).build()))
            .build();
        when(staffRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(staff));
        when(dashboard.findStaffActivityTickets(new StaffIdentity("Alice", "alice", null), WEEK_AGO, 200))
            .thenReturn(List.of(ticket));

        assertThat(service.getRecentActivity(server, "alice@example.com", 1, 7)).singleElement()
            .satisfies(activity -> assertThat(activity.description()).isEqualTo("Bob replied to General Support ticket"));
    }

    @Test
    void aPlayerChosenNameIsNeverShownAsTheStaffMembersOwnAction() {
        String uuid = "11111111-2222-3333-4444-555555555555";
        Staff staff = Staff.builder().id("staff-1").username("Alice").assignedMinecraftUuid(uuid.replace("-", "")).build();
        Ticket spoofed = Ticket.builder()
            .id("SUPPORT-1")
            .type(TicketCategory.SUPPORT)
            .creatorName("Alice")
            .created(NOW.minusSeconds(60))
            .replies(List.of(TicketReply.builder().name("Alice").created(NOW.minusSeconds(30)).build()))
            .build();
        Ticket own = Ticket.builder()
            .id("SUPPORT-2")
            .type(TicketCategory.SUPPORT)
            .creatorName("AliceInGame")
            .creatorUuid(uuid)
            .created(NOW.minusSeconds(20))
            .replies(List.of(TicketReply.builder().name("Alice").staff(true).created(NOW.minusSeconds(10)).build()))
            .build();
        when(staffRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(staff));
        when(dashboard.findStaffActivityTickets(new StaffIdentity("Alice", "alice", uuid), WEEK_AGO, 200))
            .thenReturn(List.of(spoofed, own));

        assertThat(service.getRecentActivity(server, "alice@example.com", 10, 7))
            .extracting(ActivityItemResponse::description)
            .containsExactly(
                "You replied to General Support ticket",
                "Created General Support ticket",
                "Alice replied to General Support ticket");
    }
}
