package gg.modl.backend.audit.service;

import gg.modl.backend.analytics.service.ReportPeriod;
import gg.modl.backend.audit.dto.response.StaffDetailsResponse;
import gg.modl.backend.audit.dto.response.StaffPerformanceResponse;
import gg.modl.backend.audit.repository.StaffActionSummary;
import gg.modl.backend.audit.repository.StaffActivityRepository;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.log.repository.AuditLogRepository;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentState;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.player.service.StaffIssuedPunishments;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.dto.response.StaffResponse;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.staff.service.StaffService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StaffPerformanceService {
    private static final String DEFAULT_ROLE = "User";
    private static final String NO_SUBJECT = "No Subject";
    private static final String NO_REASON = "No reason provided";
    private static final String UNKNOWN_PLAYER = "Unknown";

    private final UnitOfWork unitOfWork;
    private final StaffActivityRepository staffActivityRepository;
    private final PunishmentRepository punishmentRepository;
    private final AuditLogRepository auditLogRepository;
    private final StaffRepository staffRepository;
    private final PunishmentTypeService punishmentTypeService;
    private final StaffService staffService;
    private final PermissionService permissionService;
    private final Clock clock;

    public List<StaffPerformanceResponse> getStaffPerformance(Server server, String period) {
        Instant now = clock.instant();
        Instant start = ReportPeriod.parse(period).startingBefore(now);
        List<Staff> allStaff = staffRepository.findAll();
        Map<String, String> roleNames = permissionService.resolveRoleNames(server,
            allStaff.stream().map(Staff::getRoleId).toList());
        StaffTotals totals = new StaffTotals(
            activityByName(staffActivityRepository.summarizeLogActivityBySource(start)),
            staffActivityRepository.countTicketResponsesByStaff(start),
            StaffIssuedPunishments.of(allStaff, punishmentRepository.countByIssuer(start)),
            staffActivityRepository.averageResponseMinutesByStaff(start));
        return allStaff.stream()
            .filter(staff -> staff.getUsername() != null)
            .map(staff -> toPerformance(staff, totals, roleNames, now))
            .sorted(Comparator.comparingInt(StaffPerformanceResponse::totalActions).reversed())
            .toList();
    }

    private static StaffPerformanceResponse toPerformance(
        Staff staff, StaffTotals totals, Map<String, String> roleNames, Instant now) {
        String name = normalized(staff.getUsername());
        long ticketActions = totals.responses().getOrDefault(name, 0L);
        long punishments = totals.punishments().issuedBy(staff);
        LoggedActivity logged = totals.logged().get(name);
        long actions = Math.max(logged != null ? logged.actions() : 0L, ticketActions + punishments);
        Instant lastActive = logged != null ? logged.lastActiveAt() : Objects.requireNonNullElse(staff.getUpdatedAt(), now);
        return new StaffPerformanceResponse(
            staff.requireId(),
            staff.getUsername(),
            roleNameOf(staff, roleNames),
            Math.toIntExact(actions),
            Math.toIntExact(ticketActions),
            Math.toIntExact(punishments),
            totals.responseMinutes().getOrDefault(name, 0),
            lastActive);
    }

    private static String roleNameOf(Staff staff, Map<String, String> roleNames) {
        String roleId = staff.getRoleId();
        return roleId != null && !roleId.isBlank() ? roleNames.getOrDefault(roleId, roleId) : DEFAULT_ROLE;
    }

    private static Map<String, LoggedActivity> activityByName(List<StaffActionSummary> summaries) {
        Map<String, LoggedActivity> activity = new HashMap<>();
        summaries.forEach(summary -> activity.merge(normalized(summary.source()),
            new LoggedActivity(summary.totalActions(), summary.lastActiveAt()), LoggedActivity::plus));
        return activity;
    }

    public StaffDetailsResponse getStaffDetails(Server server, String username, String period) {
        Instant now = clock.instant();
        Instant start = ReportPeriod.parse(period).startingBefore(now);
        Optional<StaffResponse> staff = staffService.getStaffByUsername(server, username);
        String staffId = staff.map(StaffResponse::id).orElse(null);
        List<String> names = new ArrayList<>(List.of(username));
        staff.map(StaffResponse::assignedMinecraftUsername)
            .filter(assigned -> !assigned.isEmpty() && !assigned.equalsIgnoreCase(username))
            .ifPresent(names::add);

        List<StaffDetailsResponse.PunishmentDetail> punishments =
            staffActivityRepository.findPunishmentDetails(names, staffId, start).stream()
                .map(found -> toPunishmentDetail(server, found, now))
                .toList();
        List<StaffDetailsResponse.TicketDetail> tickets = staffActivityRepository.findTicketDetails(username, start).stream()
            .map(ticket -> new StaffDetailsResponse.TicketDetail(
                ticket.id(),
                Objects.requireNonNullElse(ticket.subject(), NO_SUBJECT),
                ticket.category().getId(),
                ticket.status().getId(),
                ticket.lastActivityAt(),
                responseMinutes(ticket.createdAt(), ticket.firstReplyAt())))
            .toList();
        Map<Instant, Long> responsesByDay = staffActivityRepository.countTicketResponsesByDay(username, start);
        List<StaffDetailsResponse.DailyActivity> daily = dailyActivity(
            staffActivityRepository.countPunishmentsByDay(names, staffId, start), responsesByDay);
        Map<Integer, Long> punishmentsByType = staffActivityRepository.countPunishmentsByType(names, staffId, start);
        List<StaffDetailsResponse.PunishmentTypeBreakdown> byType = punishmentsByType.entrySet().stream()
            .map(entry -> new StaffDetailsResponse.PunishmentTypeBreakdown(
                punishmentTypeService.getPunishmentTypeName(server, entry.getKey()), Math.toIntExact(entry.getValue())))
            .toList();
        int evidenceUploads =
            Math.toIntExact(unitOfWork.report(() -> auditLogRepository.countEvidenceUploads(username, start)));
        int averageResponse = staffActivityRepository.averageResponseMinutesOf(username, start);

        return new StaffDetailsResponse(username, period, punishments, tickets, daily, byType,
            new StaffDetailsResponse.Summary(
                Math.toIntExact(total(punishmentsByType)), Math.toIntExact(total(responsesByDay)), averageResponse, evidenceUploads));
    }

    private StaffDetailsResponse.PunishmentDetail toPunishmentDetail(Server server, PunishmentRecord found, Instant now) {
        Punishment punishment = found.punishment();
        Long duration = punishment.data().duration();
        return new StaffDetailsResponse.PunishmentDetail(
            punishment.getId(),
            found.playerMinecraftUuid(),
            Objects.requireNonNullElse(found.playerUsername(), UNKNOWN_PLAYER),
            punishmentTypeService.getPunishmentTypeName(server, punishment.getTypeOrdinal()),
            Objects.requireNonNullElse(punishment.data().reason(), NO_REASON),
            duration != null ? duration.toString() : null,
            punishment.getIssued(),
            !PunishmentState.activeUntil(punishment).isBefore(now),
            punishment.hasRollback(),
            punishment.enforcementCategory().displayName());
    }

    private static List<StaffDetailsResponse.DailyActivity> dailyActivity(
        Map<Instant, Long> punishments, Map<Instant, Long> responses) {
        Set<Instant> days = new TreeSet<>(punishments.keySet());
        days.addAll(responses.keySet());
        return days.stream()
            .map(day -> new StaffDetailsResponse.DailyActivity(
                LocalDate.ofInstant(day, ZoneOffset.UTC).toString(),
                Math.toIntExact(punishments.getOrDefault(day, 0L)),
                Math.toIntExact(responses.getOrDefault(day, 0L))))
            .toList();
    }

    private static long total(Map<?, Long> counts) {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }

    private static int responseMinutes(Instant createdAt, Instant firstReplyAt) {
        return (int) Duration.between(createdAt, firstReplyAt).toMinutes();
    }

    private static String normalized(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private record LoggedActivity(long actions, Instant lastActiveAt) {
        LoggedActivity plus(LoggedActivity other) {
            return new LoggedActivity(actions + other.actions,
                lastActiveAt.isAfter(other.lastActiveAt) ? lastActiveAt : other.lastActiveAt);
        }
    }

    private record StaffTotals(
        Map<String, LoggedActivity> logged,
        Map<String, Long> responses,
        StaffIssuedPunishments punishments,
        Map<String, Integer> responseMinutes) {
    }
}
