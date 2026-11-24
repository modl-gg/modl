package gg.modl.backend.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.player.repository.IssuerCount;
import gg.modl.backend.audit.dto.response.StaffDetailsResponse;
import gg.modl.backend.audit.repository.StaffActionSummary;
import gg.modl.backend.audit.repository.StaffActivityRepository;
import gg.modl.backend.audit.repository.StaffTicketDetail;
import gg.modl.backend.log.repository.AuditLogRepository;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.staff.service.StaffService;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StaffPerformanceServiceTest {
    private static final String USERNAME = "Byteful";
    private static final Instant NOW = Instant.parse("2026-01-02T00:00:00Z");
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final List<Instant> FIRST_REPLIES =
        List.of(CREATED.plusSeconds(330), CREATED.plusSeconds(600), CREATED.plusSeconds(120));

    private final Server server = TestServers.server("Demo", "admin@example.com");
    private final StaffActivityRepository activity = mock(StaffActivityRepository.class);
    private final PunishmentRepository punishments = mock(PunishmentRepository.class);
    private final StaffRepository staffRepository = mock(StaffRepository.class);
    private final StaffService staffService = mock(StaffService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final PunishmentTypeService types = mock(PunishmentTypeService.class);
    private final StaffPerformanceService service = new StaffPerformanceService(new ImmediateUnitOfWork(), activity,
        punishments, mock(AuditLogRepository.class), staffRepository, types, staffService, permissions, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void staffPerformanceAndStaffDetailsShareAvgResponseTime() {
        when(staffRepository.findAll()).thenReturn(List.of(Staff.builder().id("k7m2qz9xv4b8c1d3").username(USERNAME).build()));
        when(permissions.resolveRoleNames(any(), any())).thenReturn(Map.of());
        when(activity.averageResponseMinutesByStaff(Instant.EPOCH)).thenReturn(Map.of("byteful", 5));
        when(activity.averageResponseMinutesOf(USERNAME, Instant.EPOCH)).thenReturn(5);
        when(staffService.getStaffByUsername(server, USERNAME)).thenReturn(Optional.empty());
        when(activity.findTicketDetails(USERNAME, Instant.EPOCH)).thenReturn(FIRST_REPLIES.stream()
            .map(reply -> new StaffTicketDetail(reply.toString(), null, TicketCategory.SUPPORT, TicketStatus.OPEN, CREATED, reply, reply))
            .toList());

        StaffDetailsResponse details = service.getStaffDetails(server, USERNAME, "all");

        assertThat(details.summary().avgResponseTime()).isEqualTo(5);
        assertThat(service.getStaffPerformance(server, "all").getFirst().avgResponseTime()).isEqualTo(5);
    }

    @Test
    void issuerIdsOfAnyFormatNamesAndMinecraftNamesAllCountForTheStaffMember() {
        Staff staff = Staff.builder().id("k7m2qz9xv4b8c1d3").username(USERNAME).assignedMinecraftUsername("ByteMC").build();
        when(staffRepository.findAll()).thenReturn(List.of(staff));
        when(permissions.resolveRoleNames(any(), any())).thenReturn(Map.of());
        when(punishments.countByIssuer(Instant.EPOCH)).thenReturn(List.of(
            new IssuerCount(PunishmentIssuerKind.STAFF, "k7m2qz9xv4b8c1d3", null, 4),
            new IssuerCount(PunishmentIssuerKind.STAFF, null, "byteful", 2),
            new IssuerCount(PunishmentIssuerKind.STAFF, null, "ByteMC", 1),
            new IssuerCount(PunishmentIssuerKind.STAFF, null, "Someone", 9),
            new IssuerCount(PunishmentIssuerKind.CONSOLE, null, "Byteful", 5)));
        when(activity.summarizeLogActivityBySource(Instant.EPOCH)).thenReturn(List.of(
            new StaffActionSummary("Byteful", 6, 0, 0, CREATED),
            new StaffActionSummary("byteful", 5, 0, 0, NOW)));

        assertThat(service.getStaffPerformance(server, "all")).singleElement().satisfies(performance -> {
            assertThat(performance.punishmentsIssued()).isEqualTo(7);
            assertThat(performance.totalActions()).isEqualTo(11);
            assertThat(performance.lastActive()).isEqualTo(NOW);
        });
    }

    @Test
    void punishmentDetailsAreActiveOnlyWhileEnforced() {
        long day = Duration.ofDays(1).toMillis();
        Punishment permanent = punishment("permanent", CREATED, null);
        Punishment endingExactlyNow = punishment("ending-now", NOW.minusMillis(day), day);
        Punishment expired = punishment("expired", NOW.minusMillis(day + 1), day);
        Punishment pardoned = punishment("pardoned", CREATED, null, PunishmentModificationType.MANUAL_PARDON);
        Punishment appealed = punishment("appealed", CREATED, null, PunishmentModificationType.APPEAL_ACCEPT);
        Punishment rolledBack = punishment("rolled-back", CREATED, null, PunishmentModificationType.ROLLBACK);
        Punishment neverStarted = punishment("never-started", CREATED, day);
        neverStarted.setStarted(null);
        Punishment queued = punishment("queued", CREATED, null);
        queued.data().setStatus("Unstarted");
        Punishment kick = punishment("kick", CREATED, null);
        kick.setTypeOrdinal(0);
        when(staffService.getStaffByUsername(server, USERNAME)).thenReturn(Optional.empty());
        when(activity.findPunishmentDetails(List.of(USERNAME), null, Instant.EPOCH)).thenReturn(List
            .of(permanent, endingExactlyNow, expired, pardoned, appealed, rolledBack, neverStarted, queued, kick).stream()
            .map(punishment -> new PunishmentRecord("internal", "uuid", "Target", punishment, 0))
            .toList());

        List<StaffDetailsResponse.PunishmentDetail> details = service.getStaffDetails(server, USERNAME, "all").punishments();

        assertThat(details).filteredOn(StaffDetailsResponse.PunishmentDetail::active)
            .extracting(StaffDetailsResponse.PunishmentDetail::id)
            .containsExactly("permanent", "ending-now", "never-started");
        assertThat(details).filteredOn(StaffDetailsResponse.PunishmentDetail::rolledBack)
            .extracting(StaffDetailsResponse.PunishmentDetail::id)
            .containsExactly("rolled-back");
        verify(activity).findPunishmentDetails(List.of(USERNAME), null, Instant.EPOCH);
    }

    @Test
    void theSummaryCountsTheWholePeriodNotTheListedRows() {
        Instant start = NOW.minus(Duration.ofDays(7));
        when(staffService.getStaffByUsername(server, USERNAME)).thenReturn(Optional.empty());
        when(activity.countPunishmentsByType(List.of(USERNAME), null, start)).thenReturn(Map.of(18, 60L, 2, 5L));
        when(activity.countTicketResponsesByDay(USERNAME, start)).thenReturn(Map.of(CREATED, 70L, NOW, 3L));
        when(types.getPunishmentTypeName(any(), anyInt())).thenReturn("Type");

        StaffDetailsResponse.Summary summary = service.getStaffDetails(server, USERNAME, "7d").summary();

        assertThat(summary.totalPunishments()).isEqualTo(65);
        assertThat(summary.totalTickets()).isEqualTo(73);
    }

    @Test
    void breakdownNamesDeletedTypes() {
        when(staffService.getStaffByUsername(server, USERNAME)).thenReturn(Optional.empty());
        when(activity.countPunishmentsByType(List.of(USERNAME), null, Instant.EPOCH)).thenReturn(Map.of(18, 3L));
        when(types.getPunishmentTypeName(server, 18)).thenReturn("Cheating (deleted)");

        assertThat(service.getStaffDetails(server, USERNAME, "all").punishmentTypeBreakdown())
            .containsExactly(new StaffDetailsResponse.PunishmentTypeBreakdown("Cheating (deleted)", 3));
    }

    private static Punishment punishment(String id, Instant startedAt, Long duration, PunishmentModificationType... changes) {
        Punishment punishment = new Punishment();
        punishment.setId(id);
        punishment.setTypeOrdinal(18);
        punishment.setIssued(startedAt);
        punishment.setStarted(startedAt);
        Map<String, Object> data = new HashMap<>();
        data.put("duration", duration);
        punishment.replaceData(data);
        List<PunishmentModification> modifications = new ArrayList<>();
        for (PunishmentModificationType change : changes) {
            modifications.add(new PunishmentModification(change.name(), change.name(), NOW, "owner", null, "reason", null, null, null));
        }
        punishment.setModifications(modifications);
        return punishment;
    }
}
