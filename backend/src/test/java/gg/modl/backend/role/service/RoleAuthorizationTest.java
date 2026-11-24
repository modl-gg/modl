package gg.modl.backend.role.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.ImmediateUnitOfWork;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.role.data.StaffRole;
import gg.modl.backend.role.repository.StaffRoleRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.service.ServerTimestampService;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.staff.service.StaffLookupCache;
import gg.modl.backend.support.TestServers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RoleAuthorizationTest {

    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String NO_AUTHORITY_MESSAGE = "You do not have authority to perform this action";
    private static final String HIGHER_AUTHORITY_MESSAGE =
        "You do not have authority over a role at or above your own level";

    private StaffRoleRepository roleRepository;
    private StaffRepository staffRepository;
    private PunishmentTypeService punishmentTypeService;
    private ServerTimestampService serverTimestampService;
    private PermissionService permissionService;
    private RoleAuthorization roleAuthorization;
    private Server server;

    @BeforeEach
    void setUp() {
        roleRepository = mock(StaffRoleRepository.class);
        staffRepository = mock(StaffRepository.class);
        punishmentTypeService = mock(PunishmentTypeService.class);
        serverTimestampService = mock(ServerTimestampService.class);
        permissionService = new PermissionService(roleRepository, punishmentTypeService, staffRepository);
        roleAuthorization = new RoleAuthorization(permissionService, staffRepository, mock(StaffLookupCache.class));
        server = TestServers.server("server", ADMIN_EMAIL);
        server.setId("server-id");
        when(punishmentTypeService.getPunishmentTypes(server)).thenReturn(List.of());
    }

    @Test
    void minecraftPerformerMapsResolvedStaffRoleIdAndIdentity() {
        givenStaff("staff-mgr", "mgr@example.com", "mgr");

        RoleAuthorization.PerformerAuthority performer = roleAuthorization.minecraftPerformer(server, "staff-mgr");

        assertTrue(performer.identified());
        assertFalse(performer.superAdmin());
        assertEquals("mgr", performer.roleId());
    }

    @Test
    void minecraftPerformerFlagsSuperAdminByEmail() {
        givenStaff("staff-admin", ADMIN_EMAIL, null);

        RoleAuthorization.PerformerAuthority performer = roleAuthorization.minecraftPerformer(server, "staff-admin");

        assertTrue(performer.superAdmin());
        assertTrue(performer.identified());
    }

    @Test
    void minecraftPerformerIsUnidentifiedForMissingOrBlankActor() {
        when(staffRepository.findById("ghost")).thenReturn(Optional.empty());

        assertFalse(roleAuthorization.minecraftPerformer(server, null).identified());
        assertFalse(roleAuthorization.minecraftPerformer(server, "  ").identified());
        assertFalse(roleAuthorization.minecraftPerformer(server, "ghost").identified());
    }

    @Test
    void effectiveRoleIdNullsSuperAdminRoleForNonAdminEmail() {
        Staff stale = Staff.builder().email("old-owner@example.com").roleId(RoleAuthorization.SUPER_ADMIN_ROLE_ID).build();

        assertNull(RoleAuthorization.effectiveRoleId(server, stale));
    }

    @Test
    void effectiveRoleIdKeepsSuperAdminRoleForAdminEmail() {
        Staff owner = Staff.builder().email(ADMIN_EMAIL).roleId(RoleAuthorization.SUPER_ADMIN_ROLE_ID).build();

        assertEquals(RoleAuthorization.SUPER_ADMIN_ROLE_ID, RoleAuthorization.effectiveRoleId(server, owner));
    }

    @Test
    void assertCanAssignMinecraftPlayerAllowsSuperAdminForAnyTarget() {
        RoleAuthorization.PerformerAuthority performer =
            new RoleAuthorization.PerformerAuthority(ADMIN_EMAIL, null, true, true);
        Staff target = Staff.builder().email("helper@example.com").build();

        assertDoesNotThrow(() -> roleAuthorization.assertCanAssignMinecraftPlayer(server, performer, target));
    }

    @Test
    void assertCanAssignMinecraftPlayerRefusesAStaffMemberAssigningTheirOwnAccount() {
        RoleAuthorization.PerformerAuthority performer =
            new RoleAuthorization.PerformerAuthority("Helper@Example.com", "helper", false, true);
        Staff target = Staff.builder().email("helper@example.com").build();

        assertThrows(ForbiddenException.class, () -> roleAuthorization.assertCanAssignMinecraftPlayer(server, performer, target));
    }

    @Test
    void assertCanAssignMinecraftPlayerDeniesOtherTargetsForNonSuperAdmin() {
        RoleAuthorization.PerformerAuthority performer =
            new RoleAuthorization.PerformerAuthority("mgr@example.com", "mgr", false, true);
        Staff target = Staff.builder().email("helper@example.com").build();

        ForbiddenException error = assertThrows(ForbiddenException.class,
            () -> roleAuthorization.assertCanAssignMinecraftPlayer(server, performer, target));
        assertEquals(NO_AUTHORITY_MESSAGE, error.getMessage());
    }

    @Test
    void requireStaffManagePassesWhenRoleGrantsExactPermission() {
        givenRole("mgr", 1, "admin.staff.manage.roles");
        RoleAuthorization.PerformerAuthority performer =
            new RoleAuthorization.PerformerAuthority("mgr@example.com", "mgr", false, true);

        assertDoesNotThrow(() -> roleAuthorization.requireStaffManage(
            server, performer, PermissionService.ADMIN_STAFF_MANAGE_ROLES));
    }

    @Test
    void requireStaffManagePassesWhenRoleGrantsViaParentPrefix() {
        givenRole("mgr", 1, "admin.staff.manage");
        RoleAuthorization.PerformerAuthority performer =
            new RoleAuthorization.PerformerAuthority("mgr@example.com", "mgr", false, true);

        assertDoesNotThrow(() -> roleAuthorization.requireStaffManage(
            server, performer, PermissionService.ADMIN_STAFF_MANAGE_ROLES));
    }

    @Test
    void requireStaffManageDeniesWhenRoleLacksPermission() {
        givenRole("helper", 3, "ticket.reply.all");
        RoleAuthorization.PerformerAuthority performer =
            new RoleAuthorization.PerformerAuthority("helper@example.com", "helper", false, true);

        ForbiddenException error = assertThrows(ForbiddenException.class,
            () -> roleAuthorization.requireStaffManage(server, performer, PermissionService.ADMIN_STAFF_MANAGE_ROLES));
        assertEquals(NO_AUTHORITY_MESSAGE, error.getMessage());
    }

    @Test
    void requireStaffManageDeniesResolvedPerformerWithNullRoleId() {
        givenStaff("staff-null-role", "orphan@example.com", null);
        RoleAuthorization.PerformerAuthority performer =
            roleAuthorization.minecraftPerformer(server, "staff-null-role");
        assertTrue(performer.identified());
        assertNull(performer.roleId());

        ForbiddenException error = assertThrows(ForbiddenException.class,
            () -> roleAuthorization.requireStaffManage(server, performer, PermissionService.ADMIN_STAFF_MANAGE_ROLES));
        assertEquals(NO_AUTHORITY_MESSAGE, error.getMessage());
    }

    @Test
    void requireStaffManageDeniesUnidentifiedPerformer() {
        RoleAuthorization.PerformerAuthority performer = RoleAuthorization.PerformerAuthority.unidentified();

        assertThrows(ForbiddenException.class,
            () -> roleAuthorization.requireStaffManage(server, performer, PermissionService.ADMIN_STAFF_MANAGE_ROLES));
    }

    @Test
    void requireStaffManageAllowsSuperAdminRegardlessOfRole() {
        RoleAuthorization.PerformerAuthority performer =
            new RoleAuthorization.PerformerAuthority("owner@example.com", null, true, true);

        assertDoesNotThrow(() -> roleAuthorization.requireStaffManage(
            server, performer, PermissionService.ADMIN_STAFF_MANAGE_ROLES));
    }

    @Test
    void permissionedNonSuperAdminManagesStrictlyLowerRole() {
        givenStaff("staff-mgr", "mgr@example.com", "mgr");
        givenRole("mgr", 1, "admin.staff.manage.roles");
        StaffRole helper = givenRole("helper", 3, "ticket.view.all", "ticket.reply.all", "appeal.modify");

        RoleAuthorization.PerformerAuthority performer =
            roleAuthorization.minecraftPerformer(server, "staff-mgr");
        boolean updated = roleService().updateRolePermissions(
            server, "helper", helper.getPermissions(), performer);

        assertTrue(updated);
    }

    @Test
    void permissionedNonSuperAdminCannotManageEqualOrHigherRole() {
        givenStaff("staff-mgr", "mgr@example.com", "mgr");
        givenRole("mgr", 2, "admin.staff.manage.roles");
        StaffRole peer = givenRole("peer", 2, "ticket.reply.all");
        StaffRole higher = givenRole("higher", 1, "ticket.reply.all");

        RoleAuthorization.PerformerAuthority performer =
            roleAuthorization.minecraftPerformer(server, "staff-mgr");
        RoleService roleService = roleService();

        ForbiddenException peerError = assertThrows(ForbiddenException.class,
            () -> roleService.updateRolePermissions(server, "peer", peer.getPermissions(), performer));
        assertEquals(HIGHER_AUTHORITY_MESSAGE, peerError.getMessage());

        ForbiddenException higherError = assertThrows(ForbiddenException.class,
            () -> roleService.updateRolePermissions(server, "higher", higher.getPermissions(), performer));
        assertEquals(HIGHER_AUTHORITY_MESSAGE, higherError.getMessage());
    }

    @Test
    void effectivePermissionIdsExpandSettingsViewIntoItsSubPermissions() {
        givenRole("admin", 1, PermissionService.ADMIN_SETTINGS_VIEW);

        List<String> effective = roleAuthorization.effectivePermissionIds(server, staffPerformer("admin"));

        assertTrue(effective.contains(PermissionService.ADMIN_SETTINGS_VIEW));
        assertTrue(effective.contains("admin.settings.view.content"));
        assertTrue(effective.contains("admin.settings.view.storage"));
        assertTrue(effective.contains(PermissionService.ADMIN_SETTINGS_VIEW_PUNISHMENTS));
        assertFalse(effective.contains(PermissionService.ADMIN_AUDIT_ROLLBACK));
    }

    @Test
    void effectivePermissionIdsForSuperAdminIncludeSuperAdminOnlyPermissions() {
        List<String> effective = roleAuthorization.effectivePermissionIds(server,
            new RoleAuthorization.PerformerAuthority(ADMIN_EMAIL, null, true, true));

        assertTrue(effective.containsAll(PermissionService.superAdminOnlyPermissionIds()));
    }

    @Test
    void effectivePermissionIdsDropStoredSuperAdminOnlyPermissionWithoutMigration() {
        givenRole("admin", 1, PermissionService.ADMIN_AUDIT_ROLLBACK, "ticket.view.all");

        List<String> effective = roleAuthorization.effectivePermissionIds(server, staffPerformer("admin"));

        assertFalse(effective.contains(PermissionService.ADMIN_AUDIT_ROLLBACK));
        assertTrue(effective.contains("ticket.view.all"));
        assertFalse(permissionService.hasPermission(server, "admin", PermissionService.ADMIN_AUDIT_ROLLBACK));
    }

    @Test
    void effectivePermissionIdsAreEmptyWithoutARole() {
        assertTrue(roleAuthorization.effectivePermissionIds(server, RoleAuthorization.PerformerAuthority.unidentified()).isEmpty());
    }

    @Test
    void grantableRoleIdRejectsTheSuperAdminRoleEvenForTheOwner() {
        givenRole(RoleAuthorization.SUPER_ADMIN_ROLE_ID, 0);

        assertThrows(ForbiddenException.class, () -> roleAuthorization.assertGrantableRoleId(
            server, ownerPerformer(), RoleAuthorization.SUPER_ADMIN_ROLE_ID));
    }

    @Test
    void grantableRoleIdLetsTheOwnerGrantAnyOtherRole() {
        StaffRole admin = givenRole("admin", 1, PermissionService.ADMIN_STAFF_MANAGE_MEMBERS);

        assertEquals(admin, roleAuthorization.assertGrantableRoleId(server, ownerPerformer(), "admin"));
    }

    @Test
    void grantableRoleIdRequiresStrictlyHigherAuthority() {
        givenRole("mgr", 2, PermissionService.ADMIN_STAFF_MANAGE_MEMBERS);
        givenRole("peer", 2);
        givenRole("admin", 1);
        StaffRole helper = givenRole("helper", 3);

        assertThrows(ForbiddenException.class, () -> roleAuthorization.assertGrantableRoleId(server, staffPerformer("mgr"), "peer"));
        assertThrows(ForbiddenException.class, () -> roleAuthorization.assertGrantableRoleId(server, staffPerformer("mgr"), "admin"));
        assertEquals(helper, roleAuthorization.assertGrantableRoleId(server, staffPerformer("mgr"), "helper"));
    }

    @Test
    void grantableRoleIdRejectsUnknownRoles() {
        assertThrows(ValidationException.class, () -> roleAuthorization.assertGrantableRoleId(server, ownerPerformer(), "missing"));
    }

    @Test
    void revokingAnInvitationRequiresStrictlyHigherAuthorityOverItsRole() {
        givenRole("mgr", 2, PermissionService.ADMIN_STAFF_MANAGE_MEMBERS);
        givenRole("peer", 2);
        givenRole("helper", 3);

        assertThrows(ForbiddenException.class, () -> roleAuthorization.assertCanRevokeInvitation(server, staffPerformer("mgr"), "peer"));
        assertDoesNotThrow(() -> roleAuthorization.assertCanRevokeInvitation(server, staffPerformer("mgr"), "helper"));
        assertDoesNotThrow(() -> roleAuthorization.assertCanRevokeInvitation(server, ownerPerformer(), "peer"));
    }

    @Test
    void invitationsForADeletedRoleCanBeRevokedByAnyIdentifiedManager() {
        givenRole("mgr", 2, PermissionService.ADMIN_STAFF_MANAGE_MEMBERS);

        assertDoesNotThrow(() -> roleAuthorization.assertCanRevokeInvitation(server, staffPerformer("mgr"), "deleted"));
        assertThrows(ForbiddenException.class, () -> roleAuthorization.assertCanRevokeInvitation(
            server, RoleAuthorization.PerformerAuthority.unidentified(), "deleted"));
    }

    private static RoleAuthorization.PerformerAuthority ownerPerformer() {
        return new RoleAuthorization.PerformerAuthority(ADMIN_EMAIL, null, true, true);
    }

    private RoleAuthorization.PerformerAuthority staffPerformer(String roleId) {
        return new RoleAuthorization.PerformerAuthority("staff@example.com", roleId, false, true);
    }

    private RoleService roleService() {
        return new RoleService(
            roleRepository,
            staffRepository,
            permissionService,
            roleAuthorization,
            mock(RankLocks.class),
            serverTimestampService,
            new ImmediateUnitOfWork(),
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    private void givenStaff(String staffId, String email, String roleId) {
        Staff staff = Staff.builder().id(staffId).email(email).roleId(roleId).build();
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
    }

    private StaffRole givenRole(String roleId, int order, String... permissions) {
        StaffRole role = StaffRole.builder()
            .id(roleId)
            .name(roleId)
            .order(order)
            .permissions(new ArrayList<>(List.of(permissions)))
            .build();
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));
        when(roleRepository.findByIdForUpdate(roleId)).thenReturn(Optional.of(role));
        return role;
    }

    @Test
    void onlyTheStoredSpellingOfTheOwnerEmailIsTheOwner() {
        assertTrue(RoleAuthorization.isSuperAdminEmail(server, " Admin@Example.com "));
        assertFalse(RoleAuthorization.isSuperAdminEmail(server, "adm\u0131n@example.com"));
        assertFalse(RoleAuthorization.isSuperAdminEmail(server, null));
    }

    @Test
    void ticketNotePermissionsAreNeverImpliedByTheirParents() {
        assertFalse(RoleAuthorization.permissionImplies(PermissionService.TICKET_VIEW_ALL, PermissionService.TICKET_VIEW_ALL_NOTES));
        assertFalse(RoleAuthorization.permissionImplies(PermissionService.TICKET_REPLY_ALL, PermissionService.TICKET_REPLY_ALL_NOTES));
        assertFalse(RoleAuthorization.permissionImplies("ticket", PermissionService.TICKET_VIEW_ALL_NOTES));
        assertTrue(RoleAuthorization.permissionImplies(PermissionService.TICKET_VIEW_ALL_NOTES, PermissionService.TICKET_VIEW_ALL_NOTES));
        assertTrue(RoleAuthorization.permissionImplies(PermissionService.TICKET_MANAGE, PermissionService.TICKET_MANAGE_TAGS));
    }

    @Test
    void aRoleHoldingTheNotePermissionGrantsItWhileTheParentAloneDoesNot() {
        StaffRole parentOnly = StaffRole.builder().id("r").name("R").permissions(List.of(PermissionService.TICKET_VIEW_ALL)).build();
        StaffRole explicit = StaffRole.builder().id("r").name("R")
            .permissions(List.of(PermissionService.TICKET_VIEW_ALL, PermissionService.TICKET_VIEW_ALL_NOTES)).build();

        assertFalse(RoleAuthorization.roleGrants(parentOnly, PermissionService.TICKET_VIEW_ALL_NOTES));
        assertTrue(RoleAuthorization.roleGrants(explicit, PermissionService.TICKET_VIEW_ALL_NOTES));
    }
}
