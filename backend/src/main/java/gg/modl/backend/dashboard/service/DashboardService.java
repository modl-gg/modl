package gg.modl.backend.dashboard.service;

import gg.modl.backend.analytics.service.PercentChange;
import gg.modl.backend.analytics.service.ReportPeriod;
import gg.modl.backend.dashboard.dto.response.ActivityItemResponse;
import gg.modl.backend.dashboard.dto.response.DashboardMetricsResponse;
import gg.modl.backend.dashboard.dto.response.MinecraftDashboardStatsResponse;
import gg.modl.backend.dashboard.dto.response.RecentPunishmentResponse;
import gg.modl.backend.dashboard.dto.response.RecentTicketResponse;
import gg.modl.backend.dashboard.repository.DashboardRepository;
import gg.modl.backend.dashboard.repository.StaffIdentity;
import gg.modl.backend.infrastructure.util.DateRangeUtil;
import gg.modl.backend.infrastructure.util.UuidUtils;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentPhase;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.player.repository.IssuerMatch;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.player.service.IssuerNameResolver;
import gg.modl.backend.player.service.PunishmentMapper;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.staff.service.StaffService;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.repository.TicketRepository;
import gg.modl.backend.ticket.repository.TicketSummary;
import gg.modl.backend.ticket.util.TicketAssigneeUtil;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DashboardService {
    public static final int MAX_DAYS = 90;

    private static final ReportPeriod RECENT_PUNISHMENT_WINDOW = new ReportPeriod(7);
    private static final int MAX_RECENT_TICKETS_LIMIT = 20;
    private static final int MAX_RECENT_PUNISHMENTS_LIMIT = 20;
    private static final int MAX_ACTIVITY_LIMIT = 100;
    private static final int MAX_QUERY_RESULTS = 200;
    private static final String NO_SUBJECT = "No Subject";
    private static final String UNKNOWN = "Unknown";

    private final DashboardRepository dashboardRepository;
    private final TicketRepository ticketRepository;
    private final PunishmentRepository punishmentRepository;
    private final StaffRepository staffRepository;
    private final StaffService staffService;
    private final PunishmentTypeService punishmentTypeService;
    private final IssuerNameResolver issuerNameResolver;
    private final Clock clock;

    public MinecraftDashboardStatsResponse getMinecraftStats(Server server) {
        Map<ResolvedCategory, Long> active = punishmentRepository.countByCategory(PunishmentPhase.ACTIVE, clock.instant());
        return new MinecraftDashboardStatsResponse(
            dashboardRepository.countUnresolvedReports(),
            dashboardRepository.countUnresolvedTickets(),
            countOnlineStaff(),
            dashboardRepository.countOnlinePlayers(),
            active.getOrDefault(ResolvedCategory.BAN, 0L),
            active.getOrDefault(ResolvedCategory.MUTE, 0L),
            active.values().stream().mapToLong(Long::longValue).sum(),
            dashboardRepository.countPlayers());
    }

    private long countOnlineStaff() {
        List<String> assignedUuids = staffRepository.findAssignedMinecraftUuids();
        return assignedUuids.isEmpty() ? 0 : dashboardRepository.countOnlineAmong(assignedUuids);
    }

    public DashboardMetricsResponse getMetrics(Server server, String period) {
        Instant now = clock.instant();
        int days = DateRangeUtil.resolveRangeDays(period);
        Instant windowStart = new ReportPeriod(days).startingBefore(now);
        Instant previousStart = new ReportPeriod(days * 2).startingBefore(now);
        return new DashboardMetricsResponse(
            dashboardRepository.countTickets(),
            dashboardRepository.countTicketsByStatus(TicketStatus.OPEN),
            dashboardRepository.countPlayers(),
            dashboardRepository.countPunishments(),
            punishmentRepository.count(PunishmentPhase.ACTIVE, now),
            staffService.countStaffIncludingSuperAdmin(server),
            PercentChange.between(
                dashboardRepository.countTicketsCreatedBetween(previousStart, windowStart),
                dashboardRepository.countTicketsCreatedAfter(windowStart)),
            PercentChange.between(
                dashboardRepository.countPlayersFirstJoinedBetween(previousStart, windowStart),
                dashboardRepository.countPlayersFirstJoinedAfter(windowStart)));
    }

    public List<RecentTicketResponse> getRecentTickets(Server server, int limit) {
        List<TicketSummary> tickets = dashboardRepository.findRecentTickets(clampLimit(limit, MAX_RECENT_TICKETS_LIMIT));
        if (tickets.isEmpty()) {
            return List.of();
        }
        Map<String, TicketReply> firstReplies =
            ticketRepository.firstReplies(tickets.stream().map(TicketSummary::getId).toList());
        return tickets.stream()
            .map(ticket -> toRecentTicket(ticket, firstReplies.get(ticket.getId())))
            .toList();
    }

    private static RecentTicketResponse toRecentTicket(TicketSummary ticket, @Nullable TicketReply firstReply) {
        return new RecentTicketResponse(
            ticket.getId(),
            Objects.requireNonNullElse(ticket.getSubject(), NO_SUBJECT),
            firstReply != null ? firstReply.getContent() : null,
            ticket.getStatus().getId(),
            ticket.getPriority().getId(),
            ticket.getCreatedAt(),
            Objects.requireNonNullElse(ticket.getCreatorName(), UNKNOWN),
            ticket.getType().getId());
    }

    public List<RecentPunishmentResponse> getRecentPunishments(Server server, int limit) {
        Instant now = clock.instant();
        List<PunishmentRecord> recent = punishmentRepository.findRecent(
            RECENT_PUNISHMENT_WINDOW.startingBefore(now), clampLimit(limit, MAX_RECENT_PUNISHMENTS_LIMIT));
        Map<String, String> staffNames = issuerNameResolver.resolveForPunishments(
            server, recent.stream().map(PunishmentRecord::punishment).toList());
        return recent.stream().map(found -> toRecentPunishment(server, found, staffNames, now)).toList();
    }

    private RecentPunishmentResponse toRecentPunishment(
        Server server, PunishmentRecord found, Map<String, String> staffNames, Instant now) {
        Punishment punishment = found.punishment();
        return new RecentPunishmentResponse(
            punishment.getId(),
            found.playerUsername(),
            found.playerMinecraftUuid(),
            punishmentTypeService.getPunishmentTypeName(server, punishment.getTypeOrdinal()),
            Objects.requireNonNullElse(punishment.data().reason(), ""),
            PunishmentMapper.resolveIssuer(punishment.getIssuerId(), punishment.getIssuerName(), staffNames),
            punishment.getIssued(),
            PunishmentPhase.of(punishment, now) == PunishmentPhase.ACTIVE);
    }

    public List<ActivityItemResponse> getRecentActivity(Server server, String staffEmail, int limit, int days) {
        Staff staff = staffRepository.findByEmail(staffEmail).orElse(null);
        if (staff == null || staff.getUsername() == null || staff.getUsername().isBlank()) {
            return List.of();
        }
        Instant cutoff = new ReportPeriod(clampLimit(days, MAX_DAYS)).startingBefore(clock.instant());
        List<ActivityItemResponse> activities = new ArrayList<>(ticketActivities(identityOf(staff), cutoff));
        activities.addAll(punishmentActivities(server, staff, cutoff));
        return activities.stream()
            .sorted(Comparator.comparing(ActivityItemResponse::time).reversed())
            .limit(clampLimit(limit, MAX_ACTIVITY_LIMIT))
            .toList();
    }

    private static StaffIdentity identityOf(Staff staff) {
        return new StaffIdentity(staff.getUsername(), TicketAssigneeUtil.normalizeSingle(staff.getUsername()),
            UuidUtils.dashed(staff.getAssignedMinecraftUuid()));
    }

    private List<ActivityItemResponse> ticketActivities(StaffIdentity staff, Instant cutoff) {
        List<Ticket> tickets = dashboardRepository.findStaffActivityTickets(staff, cutoff, MAX_QUERY_RESULTS);
        List<ActivityItemResponse> activities = new ArrayList<>();
        for (Ticket ticket : tickets) {
            boolean createdByStaff = staff.minecraftUuid() != null && staff.minecraftUuid().equals(ticket.getCreatorUuid());
            if (createdByStaff && ticket.getCreated().isAfter(cutoff)) {
                activities.add(ticketCreated(ticket));
            }
            ticket.getReplies().stream()
                .filter(reply -> reply.getCreated().isAfter(cutoff))
                .forEach(reply -> activities.add(ticketReplied(ticket, reply, staff.username())));
        }
        return activities;
    }

    private static ActivityItemResponse ticketCreated(Ticket ticket) {
        return new ActivityItemResponse(
            "ticket-created-" + ticket.getId(),
            "new_ticket",
            "blue",
            "Created ticket: " + Objects.requireNonNullElse(ticket.getSubject(), NO_SUBJECT),
            ticket.getCreated(),
            "Created " + ticket.getType().getDisplayName() + " ticket",
            List.of(viewTicket(ticket)));
    }

    private static ActivityItemResponse ticketReplied(Ticket ticket, TicketReply reply, String username) {
        boolean ownReply = reply.isStaff() && username.equalsIgnoreCase(reply.getName());
        String author = ownReply ? "You" : Objects.requireNonNullElse(reply.getName(), UNKNOWN);
        return new ActivityItemResponse(
            "ticket-reply-" + ticket.getId() + "-" + reply.getCreated().toEpochMilli(),
            "mod_action",
            ownReply ? "green" : "blue",
            (ownReply ? "My reply" : "New reply") + " on ticket: " + Objects.requireNonNullElse(ticket.getSubject(), NO_SUBJECT),
            reply.getCreated(),
            author + " replied to " + ticket.getType().getDisplayName() + " ticket",
            List.of(viewTicket(ticket)));
    }

    private static ActivityItemResponse.ActivityAction viewTicket(Ticket ticket) {
        return new ActivityItemResponse.ActivityAction("View Ticket", "/panel/tickets/" + ticket.getId(), true);
    }

    private List<ActivityItemResponse> punishmentActivities(Server server, Staff staff, Instant cutoff) {
        return punishmentRepository
            .findRecentByIssuer(new IssuerMatch(staff.getUsername(), staff.getId()), cutoff, MAX_QUERY_RESULTS)
            .stream()
            .map(found -> punishmentApplied(server, found))
            .toList();
    }

    private ActivityItemResponse punishmentApplied(Server server, PunishmentRecord found) {
        Punishment punishment = found.punishment();
        String typeName = punishmentTypeService.getPunishmentTypeName(server, punishment.getTypeOrdinal());
        return new ActivityItemResponse(
            "punishment-" + punishment.getId(),
            "new_punishment",
            "red",
            "Applied " + typeName + " to " + found.playerUsername(),
            punishment.getIssued(),
            "Applied " + typeName + " punishment",
            List.of(new ActivityItemResponse.ActivityAction("View Player", "/panel/players/" + found.playerMinecraftUuid(), true)));
    }

    private static int clampLimit(int value, int max) {
        return Math.clamp(value, 1, max);
    }
}
