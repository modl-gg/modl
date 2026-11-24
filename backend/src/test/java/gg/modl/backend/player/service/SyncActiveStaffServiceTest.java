package gg.modl.backend.player.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.role.data.StaffRole;
import gg.modl.backend.role.repository.StaffRoleRepository;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.staff.service.StaffLookupCache;
import gg.modl.backend.support.TestServers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SyncActiveStaffServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String STAFF_UUID = "11111111-2222-3333-4444-555555555555";

    private final Server server = TestServers.server("server", "admin@example.com");
    private final StaffRepository staffRepository = mock(StaffRepository.class);
    private final StaffRoleRepository roleRepository = mock(StaffRoleRepository.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final SyncActiveStaffService service = new SyncActiveStaffService(staffRepository, roleRepository,
        new RoleAuthorization(permissionService, staffRepository, mock(StaffLookupCache.class)), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aZonedIpv6AddressKeepsItsTwoFactorSession() {
        givenSession("fe80::1");

        assertThat(sessionValidFrom("fe80::1%eth0")).isEqualTo(true);
    }

    @Test
    void anotherAddressHasNoTwoFactorSession() {
        givenSession("fe80::1");

        assertThat(sessionValidFrom("fe80::2%eth0")).isEqualTo(false);
        assertThat(sessionValidFrom(null)).isEqualTo(false);
    }

    @Test
    void thePluginReceivesTheEffectivePermissionsOfTheRoleSoItNeverExpandsParentsItself() {
        when(permissionService.getGrantablePermissionIds(server)).thenReturn(List.of(PermissionService.TICKET_VIEW_ALL,
            PermissionService.TICKET_VIEW_ALL_NOTES, PermissionService.PUNISHMENT_MODIFY, PermissionService.PUNISHMENT_MODIFY_PARDON,
            PermissionService.PLAYER_NOTE));
        when(roleRepository.findByIds(any())).thenReturn(List.of(StaffRole.builder().id("helper").name("Helper")
            .permissions(List.of(PermissionService.TICKET_VIEW_ALL, PermissionService.PUNISHMENT_MODIFY, "admin.audit.rollback"))
            .build()));
        givenSession("fe80::1");

        assertThat(service.getActiveStaffMembers(server, Map.of()).getFirst().get("permissions")).isEqualTo(List.of(
            PermissionService.TICKET_VIEW_ALL, PermissionService.PUNISHMENT_MODIFY, PermissionService.PUNISHMENT_MODIFY_PARDON));
    }

    private void givenSession(String ip) {
        when(staffRepository.findAssignedMinecraftStaff()).thenReturn(List.of(Staff.builder()
            .id("staff-1")
            .email("mod@example.com")
            .username("Mod")
            .roleId("helper")
            .assignedMinecraftUuid(STAFF_UUID)
            .twoFactorSessionIp(ip)
            .twoFactorSessionExpiresAt(NOW.toEpochMilli() + 60_000)
            .build()));
    }

    private Object sessionValidFrom(String ip) {
        Map<String, String> ips = ip == null ? Map.of() : Map.of(STAFF_UUID, ip);
        return service.getActiveStaffMembers(server, ips).getFirst().get("twoFactorSessionValid");
    }
}
