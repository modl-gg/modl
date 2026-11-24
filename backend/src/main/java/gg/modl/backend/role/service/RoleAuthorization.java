package gg.modl.backend.role.service;

import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.role.data.StaffRole;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.staff.service.StaffLookupCache;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RoleAuthorization {
    public static final String SUPER_ADMIN_ROLE_ID = "super-admin";

    public static final String SUPER_ADMIN_ROLE_NAME = "Super Admin";
    private static final String NO_AUTHORITY_MESSAGE = "You do not have authority to perform this action";
    private static final String NO_GRANT_AUTHORITY_MESSAGE = "You do not have authority to grant this role";
    private static final String HIGHER_AUTHORITY_MESSAGE = "You do not have authority over a role at or above your own level";
    private static final String PROTECTED_ADMIN_MESSAGE = "Cannot modify the server administrator";
    private static final String UNKNOWN_ROLE_MESSAGE = "Unknown staff role";

    private final PermissionService permissionService;
    private final StaffRepository staffRepository;
    private final StaffLookupCache staffLookupCache;

    public record PerformerAuthority(@Nullable String email, @Nullable String roleId, boolean superAdmin, boolean identified) {
        public static PerformerAuthority unidentified() {
            return new PerformerAuthority(null, null, false, false);
        }

        public Optional<String> rankedEmail() {
            return identified && !superAdmin ? Optional.ofNullable(email) : Optional.empty();
        }
    }

    public PerformerAuthority panelPerformer(Server server, @Nullable String sessionEmail) {
        if (sessionEmail == null || sessionEmail.isBlank()) {
            return PerformerAuthority.unidentified();
        }
        if (isSuperAdminEmail(server, sessionEmail)) {
            return new PerformerAuthority(sessionEmail, null, true, true);
        }
        return new PerformerAuthority(sessionEmail, panelRoleId(server, sessionEmail), false, true);
    }

    public @Nullable String panelRoleId(Server server, @Nullable String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return staffLookupCache.findByEmail(server, email)
            .map(staff -> effectiveRoleId(server, staff))
            .orElse(null);
    }

    public PerformerAuthority minecraftPerformer(Server server, @Nullable String actingStaffId) {
        if (actingStaffId == null || actingStaffId.isBlank()) {
            return PerformerAuthority.unidentified();
        }
        return staffRepository.findById(actingStaffId)
            .map(staff -> new PerformerAuthority(staff.getEmail(), effectiveRoleId(server, staff),
                isSuperAdminEmail(server, staff.getEmail()), true))
            .orElseGet(PerformerAuthority::unidentified);
    }

    public List<String> effectivePermissionIds(Server server, PerformerAuthority performer) {
        if (performer.superAdmin()) {
            return permissionService.getAllPermissionIds(server);
        }
        return permissionService.getRoleById(server, performer.roleId())
            .map(effectivePermissionsOf(server))
            .orElseGet(List::of);
    }

    public Function<StaffRole, List<String>> effectivePermissionsOf(Server server) {
        List<String> grantable = permissionService.getGrantablePermissionIds(server);
        return role -> grantable.stream().filter(permissionId -> roleGrants(role, permissionId)).toList();
    }

    public void requireStaffManage(Server server, PerformerAuthority performer, String requiredManagePermission) {
        if (!performer.identified()) {
            throw new ForbiddenException(NO_AUTHORITY_MESSAGE);
        }
        if (performer.superAdmin()) {
            return;
        }
        if (performer.roleId() == null
            || !permissionService.hasPermission(server, performer.roleId(), requiredManagePermission)) {
            throw new ForbiddenException(NO_AUTHORITY_MESSAGE);
        }
    }

    public void assertCanAssignMinecraftPlayer(Server server, PerformerAuthority performer, Staff target) {
        if (performer.superAdmin()) {
            return;
        }
        assertCanActOnStaff(server, performer, target);
    }

    public void assertCanActOnStaff(Server server, PerformerAuthority performer, Staff target) {
        if (isSuperAdminEmail(server, target.getEmail())) {
            throw new ForbiddenException(PROTECTED_ADMIN_MESSAGE);
        }
        if (performer.superAdmin()) {
            return;
        }
        StaffRole performerRole = requirePerformerRole(server, performer);
        StaffRole targetRole = permissionService.getRoleById(server, target.getRoleId())
            .orElseThrow(() -> new ForbiddenException(HIGHER_AUTHORITY_MESSAGE));
        assertHigherAuthority(performerRole, targetRole);
    }

    public StaffRole assertGrantableRole(Server server, PerformerAuthority performer, String targetRoleName) {
        return assertGrantable(server, performer, permissionService.getRoleByName(server, targetRoleName)
            .orElseThrow(() -> new ValidationException(UNKNOWN_ROLE_MESSAGE)));
    }

    public StaffRole assertGrantableRoleId(Server server, PerformerAuthority performer, @Nullable String targetRoleId) {
        return assertGrantable(server, performer, permissionService.getRoleById(server, targetRoleId)
            .orElseThrow(() -> new ValidationException(UNKNOWN_ROLE_MESSAGE)));
    }

    public void assertCanRevokeInvitation(Server server, PerformerAuthority performer, String invitedRoleId) {
        if (performer.superAdmin()) {
            return;
        }
        StaffRole performerRole = requirePerformerRole(server, performer);
        permissionService.getRoleById(server, invitedRoleId)
            .ifPresent(invitedRole -> assertHigherAuthority(performerRole, invitedRole));
    }

    private StaffRole assertGrantable(Server server, PerformerAuthority performer, StaffRole targetRole) {
        if (isSuperAdminRole(targetRole)) {
            throw new ForbiddenException(NO_GRANT_AUTHORITY_MESSAGE);
        }
        if (performer.superAdmin()) {
            return targetRole;
        }
        StaffRole performerRole = requirePerformerRole(server, performer);
        assertHigherAuthority(performerRole, targetRole);
        return targetRole;
    }

    public StaffRole requirePerformerRole(Server server, PerformerAuthority performer) {
        if (!performer.identified() || performer.roleId() == null || performer.roleId().isBlank()) {
            throw new ForbiddenException(NO_AUTHORITY_MESSAGE);
        }
        return permissionService.getRoleById(server, performer.roleId())
            .orElseThrow(() -> new ForbiddenException(NO_AUTHORITY_MESSAGE));
    }

    public void assertHigherAuthority(StaffRole performer, StaffRole target) {
        if (performer.getOrder() >= target.getOrder()) {
            throw new ForbiddenException(HIGHER_AUTHORITY_MESSAGE);
        }
    }

    public static boolean roleGrants(StaffRole role, String permission) {
        if (isSuperAdminRole(role)) {
            return true;
        }
        List<String> permissions = role.getPermissions();
        if (permissions == null) {
            return false;
        }
        for (String held : permissions) {
            if (permissionImplies(held, permission)) {
                return true;
            }
        }
        return false;
    }

    public static boolean permissionImplies(String heldPermission, String requestedPermission) {
        return heldPermission.equals(requestedPermission)
            || (requestedPermission.startsWith(heldPermission + ".")
                && !PermissionService.NOT_IMPLIED_BY_PARENT.contains(requestedPermission));
    }

    public static boolean isSuperAdminRole(StaffRole role) {
        return SUPER_ADMIN_ROLE_ID.equals(role.getId()) || SUPER_ADMIN_ROLE_NAME.equals(role.getName());
    }

    public static boolean isSuperAdminRoleId(String roleId) {
        return SUPER_ADMIN_ROLE_ID.equals(roleId);
    }

    public static boolean isSuperAdminEmail(Server server, String email) {
        String owner = EmailAddressUtil.normalize(server.getAdminEmail());
        return owner != null && owner.equals(EmailAddressUtil.normalize(email));
    }

    public static @Nullable String effectiveRoleId(Server server, Staff staff) {
        String roleId = staff.getRoleId();
        if (SUPER_ADMIN_ROLE_ID.equals(roleId) && !isSuperAdminEmail(server, staff.getEmail())) {
            return null;
        }
        return roleId;
    }
}
