package gg.modl.backend.staff.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.player.repository.IssuerCount;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.dto.response.MinecraftStaffSummaryResponse;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.support.TestServers;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MinecraftStaffServiceSummaryTest {

    private final StaffRepository staffRepository = mock(StaffRepository.class);
    private final PlayerRepository playerRepository = mock(PlayerRepository.class);
    private final PunishmentRepository punishmentRepository = mock(PunishmentRepository.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final MinecraftStaffService service = MinecraftStaffServices.unlocked(
        staffRepository, playerRepository, punishmentRepository, permissionService, Clock.systemUTC());

    @Test
    void summaryHandlesStaffWithoutLinkedMinecraftAccount() {
        Server server = server();
        Staff unlinked = Staff.builder()
            .id("unlinked-id")
            .email("unlinked@example.com")
            .username("Unlinked")
            .roleId("admin")
            .build();
        when(staffRepository.findAll()).thenReturn(List.of(unlinked));
        when(punishmentRepository.countByIssuer(Instant.EPOCH)).thenReturn(List.of());
        when(permissionService.getRolesByIds(any(Server.class), any())).thenReturn(Map.of());

        List<MinecraftStaffSummaryResponse> summary =
            assertDoesNotThrow(() -> service.getMinecraftStaffSummary(server));

        assertEquals(1, summary.size());
        MinecraftStaffSummaryResponse row = summary.get(0);
        assertNull(row.minecraftUuid());
        assertEquals(0L, row.totalPlaytimeMs());
        assertNull(row.lastServer());
        verifyNoInteractions(playerRepository);
    }

    @Test
    void summaryReportsPlaytimeAndLastServerForLinkedStaff() {
        Server server = server();
        String uuid = "11111111-1111-1111-1111-111111111111";
        Staff linked = Staff.builder()
            .id("linked-id")
            .email("linked@example.com")
            .username("Linked")
            .roleId("admin")
            .assignedMinecraftUuid(uuid)
            .assignedMinecraftUsername("LinkedMc")
            .build();
        Player player = Player.builder()
            .minecraftUuid(UUID.fromString(uuid))
            .data(Map.of("totalPlaytimeSeconds", 120, "lastServer", "lobby"))
            .build();
        when(staffRepository.findAll()).thenReturn(List.of(linked));
        when(playerRepository.findByMinecraftUuids(List.of(uuid))).thenReturn(List.of(player));
        when(punishmentRepository.countByIssuer(Instant.EPOCH)).thenReturn(List.of());
        when(permissionService.getRolesByIds(any(Server.class), any())).thenReturn(Map.of());

        List<MinecraftStaffSummaryResponse> summary = service.getMinecraftStaffSummary(server);

        assertEquals(1, summary.size());
        MinecraftStaffSummaryResponse row = summary.get(0);
        assertEquals(uuid, row.minecraftUuid());
        assertEquals(120000L, row.totalPlaytimeMs());
        assertEquals("lobby", row.lastServer());
    }

    @Test
    void summaryAddsThePunishmentsIssuedUnderEachIdentityOfAStaffMemberOnce() {
        Server server = server();
        Staff member = Staff.builder()
            .id("member-id")
            .email("member@example.com")
            .username("Member")
            .assignedMinecraftUsername("MemberMc")
            .build();
        when(staffRepository.findAll()).thenReturn(List.of(member));
        when(punishmentRepository.countByIssuer(Instant.EPOCH)).thenReturn(List.of(
            new IssuerCount(PunishmentIssuerKind.STAFF, "member-id", "Renamed", 3),
            new IssuerCount(PunishmentIssuerKind.STAFF, null, "member", 4),
            new IssuerCount(PunishmentIssuerKind.STAFF, null, "MemberMc", 2),
            new IssuerCount(PunishmentIssuerKind.AUTOMATED, null, "Member", 5),
            new IssuerCount(PunishmentIssuerKind.STAFF, null, "Other", 9)));
        when(permissionService.getRolesByIds(any(Server.class), any())).thenReturn(Map.of());

        assertEquals(9, service.getMinecraftStaffSummary(server).get(0).punishmentsIssuedCount());
    }

    private Server server() {
        Server server = TestServers.server("Server", "owner@example.com");
        server.setId("server-id");
        return server;
    }
}
