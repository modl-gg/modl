package gg.modl.backend.player.service;

import gg.modl.backend.support.TestServers;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.player.repository.PlayerPunishmentLock;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.log.service.LogService;
import gg.modl.backend.role.service.MinecraftIssuerAuthorizer;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.service.StaffLookupCache;
import gg.modl.backend.support.TestAddressSettings;
import gg.modl.backend.ticket.service.TicketService;
import gg.modl.backend.webhook.WebhookPublisher;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PunishmentPermissionAuthorityTest {

    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String STALE_SUPER_ADMIN_EMAIL = "old-owner@example.com";
    private static final String MODERATOR_EMAIL = "mod@example.com";
    private static final String MODERATOR_ROLE_ID = "moderator";
    private static final int BAN_ORDINAL = 2;
    private static final String BAN_APPLY_PERMISSION = "punishment.apply.2";

    private Server server;
    private PermissionService permissionService;
    private StaffRepository staffRepository;
    private StaffLookupCache staffLookupCache;
    private PunishmentLifecycleService lifecycleService;

    @BeforeEach
    void setUp() {
        server = TestServers.server("server", ADMIN_EMAIL);
        server.setId("server-id");

        permissionService = mock(PermissionService.class);
        staffRepository = mock(StaffRepository.class);
        staffLookupCache = mock(StaffLookupCache.class);

        PunishmentTypeService punishmentTypeService = mock(PunishmentTypeService.class);
        PunishmentType banType = new PunishmentType();
        banType.setName("Manual Ban");
        when(punishmentTypeService.getPunishmentTypeByOrdinal(server, BAN_ORDINAL)).thenReturn(Optional.of(banType));

        lifecycleService = new PunishmentLifecycleService(
            new DirectUnitOfWork(),
            Clock.systemUTC(),
            mock(PlayerRepository.class),
            mock(PunishmentRepository.class),
            mock(PlayerPunishmentLock.class),
            mock(EvidenceFileAttachments.class),
            mock(TicketService.class),
            mock(PlayerStatusCalculator.class),
            punishmentTypeService,
            mock(PunishmentDurationCalculator.class),
            mock(IssuerNameResolver.class),
            permissionService,
            new RoleAuthorization(permissionService, staffRepository, staffLookupCache),
            mock(WebhookPublisher.class),
            mock(PunishmentRealtimePublisher.class),
            mock(LogService.class),
            mock(PunishmentPromotion.class),
            mock(MinecraftIssuerAuthorizer.class),
            TestAddressSettings.defaults()
        );
    }

    private void givenStaff(String email, String roleId) {
        Staff staff = Staff.builder().email(email).roleId(roleId).build();
        when(staffLookupCache.findByEmail(server, email)).thenReturn(Optional.of(staff));
    }

    @Test
    void staleSuperAdminRoleOnANonAdminEmailGrantsNothing() {
        givenStaff(STALE_SUPER_ADMIN_EMAIL, RoleAuthorization.SUPER_ADMIN_ROLE_ID);
        when(permissionService.hasPermission(server, RoleAuthorization.SUPER_ADMIN_ROLE_ID, BAN_APPLY_PERMISSION))
            .thenReturn(true);

        assertThrows(ForbiddenException.class,
            () -> lifecycleService.validatePunishmentPermission(server, STALE_SUPER_ADMIN_EMAIL, BAN_ORDINAL));
    }

    @Test
    void serverAdministratorBypassesTypePermissions() {
        assertDoesNotThrow(() -> lifecycleService.validatePunishmentPermission(server, ADMIN_EMAIL, BAN_ORDINAL));
    }

    @Test
    void staffWithTheApplyPermissionIsAllowed() {
        givenStaff(MODERATOR_EMAIL, MODERATOR_ROLE_ID);
        when(permissionService.hasPermission(server, MODERATOR_ROLE_ID, BAN_APPLY_PERMISSION)).thenReturn(true);

        assertDoesNotThrow(() -> lifecycleService.validatePunishmentPermission(server, MODERATOR_EMAIL, BAN_ORDINAL));
    }

    @Test
    void staffWithoutTheApplyPermissionIsRejected() {
        givenStaff(MODERATOR_EMAIL, MODERATOR_ROLE_ID);
        when(permissionService.hasPermission(server, MODERATOR_ROLE_ID, BAN_APPLY_PERMISSION)).thenReturn(false);

        assertThrows(ForbiddenException.class,
            () -> lifecycleService.validatePunishmentPermission(server, MODERATOR_EMAIL, BAN_ORDINAL));
    }

    @Test
    void anEmailWithNoStaffRecordIsRejected() {
        when(staffLookupCache.findByEmail(server, "ghost@example.com")).thenReturn(Optional.empty());

        assertThrows(ForbiddenException.class,
            () -> lifecycleService.validatePunishmentPermission(server, "ghost@example.com", BAN_ORDINAL));
    }

    @Test
    void anUnauthenticatedCallerIsRejected() {
        assertThrows(ForbiddenException.class,
            () -> lifecycleService.validatePunishmentPermission(server, null, BAN_ORDINAL));
        assertThrows(ForbiddenException.class,
            () -> lifecycleService.validatePunishmentPermission(server, "  ", BAN_ORDINAL));
    }

    @Test
    void theApplyPermissionFollowsTheTypeOrdinalNotItsName() {
        givenStaff(MODERATOR_EMAIL, MODERATOR_ROLE_ID);
        when(permissionService.hasPermission(server, MODERATOR_ROLE_ID, "punishment.apply.manual-ban")).thenReturn(true);

        assertThrows(ForbiddenException.class,
            () -> lifecycleService.validatePunishmentPermission(server, MODERATOR_EMAIL, BAN_ORDINAL));
    }
}
