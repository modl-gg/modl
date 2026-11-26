package gg.modl.backend.role.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.role.data.Permission;
import gg.modl.backend.role.data.StaffRole;
import gg.modl.backend.role.dto.request.ReorderRolesRequest;
import gg.modl.backend.role.dto.request.RoleRequest;
import gg.modl.backend.role.dto.response.RoleResponse;
import gg.modl.backend.role.repository.StaffRoleRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.service.ServerTimestampService;
import gg.modl.backend.settings.data.DefaultPunishmentTypes;
import gg.modl.backend.staff.repository.StaffRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RoleService {
    public static final String HELPER_ROLE_ID = "helper";
    public static final String HELPER_ROLE_NAME = "Helper";
    private static final int FIRST_CUSTOM_ROLE_ORDER = 4;
    private static final int HIGHEST_ROLE_ORDER = 1_000_000;

    private final StaffRoleRepository staffRoleRepository;
    private final StaffRepository staffRepository;
    private final PermissionService permissionService;
    private final RoleAuthorization roleAuthorization;
    private final RankLocks rankLocks;
    private final ServerTimestampService serverTimestampService;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    private final AtomicBoolean orderingRepaired = new AtomicBoolean();

    public List<RoleResponse> getAllRoles(Server server) {
        repairCustomRoleOrderingOnce();

        return unitOfWork.read(() -> {
            List<StaffRole> roles = staffRoleRepository.findAllOrdered();
            Map<String, Integer> roleCounts = staffRepository.countByRoleId();
            return roles.stream()
                .map(role -> toRoleResponse(role, roleCounts.getOrDefault(role.getId(), 0)))
                .toList();
        });
    }

    private void repairCustomRoleOrderingOnce() {
        if (orderingRepaired.get()) {
            return;
        }
        if (!staffRoleRepository.findCustomRolesWithOrderZero().isEmpty()) {
            unitOfWork.write(this::moveUnorderedCustomRolesLast);
        }
        orderingRepaired.set(true);
    }

    private void moveUnorderedCustomRolesLast() {
        List<StaffRole> unordered = staffRoleRepository.findCustomRolesWithOrderZero();
        if (unordered.isEmpty()) {
            return;
        }
        List<StaffRole> oldestFirst = unordered.stream()
            .sorted(Comparator.comparing(StaffRole::getCreatedAt).thenComparing(StaffRole::getId))
            .toList();

        Map<String, Integer> orderById = new LinkedHashMap<>();
        int order = Math.max(nextRoleOrder(), FIRST_CUSTOM_ROLE_ORDER);
        for (StaffRole role : oldestFirst) {
            orderById.put(role.getId(), order++);
        }
        staffRoleRepository.repairOrderFromZero(orderById);
        unitOfWork.afterCommit(permissionService::evictPermissionCache);
    }

    private RoleResponse toRoleResponse(StaffRole role, int userCount) {
        return new RoleResponse(
            role.getId(),
            role.getName(),
            role.getDescription(),
            role.getPermissions(),
            role.isDefault(),
            role.getOrder(),
            userCount,
            role.getCreatedAt(),
            role.getUpdatedAt()
        );
    }

    public Optional<RoleResponse> getRoleById(Server server, String id) {
        return unitOfWork.read(() -> staffRoleRepository.findById(id)
            .map(role -> toRoleResponse(role, staffRepository.countByRoleId(role.getId()))));
    }

    public boolean updateRolePermissions(Server server, String id, List<String> permissions,
                                         RoleAuthorization.PerformerAuthority performer) {
        if (RoleAuthorization.isSuperAdminRoleId(id)) {
            throw new ForbiddenException("Cannot modify Super Admin role");
        }

        roleAuthorization.requireStaffManage(server, performer, PermissionService.ADMIN_STAFF_MANAGE_ROLES);

        return unitOfWork.write(() -> {
            StaffRole targetRole = lockRoleForEdit(server, performer, id).orElse(null);
            if (targetRole == null) {
                return false;
            }

            List<String> requested = validPermissions(server, permissions != null ? permissions : List.of());
            List<String> granted = requested;
            if (!performer.superAdmin()) {
                StaffRole performerRole = roleAuthorization.requirePerformerRole(server, performer);
                roleAuthorization.assertHigherAuthority(performerRole, targetRole);
                granted = filterToGrantableOrExisting(performerRole, targetRole, requested);
            }

            targetRole.setPermissions(new ArrayList<>(granted));
            targetRole.setUpdatedAt(clock.instant());
            staffRoleRepository.save(targetRole);
            invalidatePermissionState(server);
            return true;
        });
    }

    private Optional<StaffRole> lockRoleForEdit(Server server, RoleAuthorization.PerformerAuthority performer, String id) {
        rankLocks.lockStaff(server, performer);
        rankLocks.lockRoles(performer, id);
        return staffRoleRepository.findByIdForUpdate(id).filter(role -> role.getId().equals(id));
    }

    private List<String> validPermissions(Server server, List<String> requested) {
        Set<String> grantable = new HashSet<>(permissionService.getGrantablePermissionIds(server));
        return requested.stream().filter(grantable::contains).distinct().toList();
    }

    private void invalidatePermissionState(Server server) {
        unitOfWork.afterCommit(permissionService::evictPermissionCache);
        serverTimestampService.updateStaffPermissionsTimestamp(server);
    }

    public RoleResponse createRole(Server server, RoleRequest request, RoleAuthorization.PerformerAuthority performer) {
        String roleName = requireRoleName(request.name());

        return unitOfWork.write(() -> {
            rankLocks.lockStaff(server, performer);
            rankLocks.lockRoles(performer);
            if (staffRoleRepository.existsByNameIgnoreCase(roleName)) {
                throw roleNameTaken();
            }

            List<String> requested = validPermissions(server, request.permissions());
            List<String> granted = requested;
            if (!performer.superAdmin()) {
                StaffRole performerRole = roleAuthorization.requirePerformerRole(server, performer);
                granted = filterToGrantablePermissions(performerRole, requested);
            }

            Instant now = clock.instant();
            StaffRole newRole = StaffRole.builder()
                .id(generateCustomRoleId(now))
                .name(roleName)
                .description(request.description())
                .permissions(new ArrayList<>(granted))
                .isDefault(false)
                .order(nextRoleOrder())
                .createdAt(now)
                .updatedAt(now)
                .build();

            staffRoleRepository.save(newRole);
            return toRoleResponse(newRole, 0);
        });
    }

    private static String generateCustomRoleId(Instant now) {
        return "custom-" + now.toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private int nextRoleOrder() {
        return staffRoleRepository.findHighestOrdered()
            .map(highestRole -> highestRole.getOrder() + 1)
            .orElse(FIRST_CUSTOM_ROLE_ORDER);
    }

    private List<String> filterToGrantablePermissions(StaffRole performerRole, List<String> permissions) {
        return permissions.stream()
            .filter(p -> RoleAuthorization.roleGrants(performerRole, p))
            .toList();
    }

    private List<String> filterToGrantableOrExisting(StaffRole performerRole, StaffRole targetRole, List<String> permissions) {
        Set<String> existing = new HashSet<>(targetRole.getPermissions() != null ? targetRole.getPermissions() : List.of());
        return permissions.stream()
            .filter(p -> RoleAuthorization.roleGrants(performerRole, p) || existing.contains(p))
            .toList();
    }

    private static String requireRoleName(String requestedName) {
        String roleName = requestedName != null ? requestedName.trim() : "";
        if (roleName.isBlank()) {
            throw new ValidationException("Role name cannot be empty");
        }
        return roleName;
    }

    private static ConflictException roleNameTaken() {
        return new ConflictException("A role with this name already exists");
    }

    public Optional<RoleResponse> updateRole(Server server, String id, RoleRequest request, RoleAuthorization.PerformerAuthority performer) {
        if (RoleAuthorization.isSuperAdminRoleId(id)) {
            throw new ForbiddenException("Cannot modify Super Admin role");
        }

        return unitOfWork.write(() -> lockRoleForEdit(server, performer, id).map(role -> {
            StaffRole performerRole = performer.superAdmin() ? null : roleAuthorization.requirePerformerRole(server, performer);
            if (performerRole != null) {
                roleAuthorization.assertHigherAuthority(performerRole, role);
            }

            String roleName = requireRoleName(request.name());
            if (staffRoleRepository.existsByNameIgnoreCaseExcludingId(roleName, id)) {
                throw roleNameTaken();
            }

            List<String> requested = validPermissions(server, request.permissions());
            List<String> granted = performerRole != null
                ? filterToGrantableOrExisting(performerRole, role, requested)
                : requested;

            role.setName(roleName);
            role.setDescription(request.description());
            role.setPermissions(new ArrayList<>(granted));
            role.setUpdatedAt(clock.instant());
            staffRoleRepository.save(role);
            invalidatePermissionState(server);
            return toRoleResponse(role, staffRepository.countByRoleId(role.getId()));
        }));
    }

    public boolean deleteRole(Server server, String id, RoleAuthorization.PerformerAuthority performer) {
        if (RoleAuthorization.isSuperAdminRoleId(id)) {
            throw new ForbiddenException("Cannot delete Super Admin role");
        }

        return unitOfWork.write(() -> {
            StaffRole role = lockRoleForEdit(server, performer, id).orElse(null);
            if (role == null) {
                return false;
            }

            if (!performer.superAdmin()) {
                StaffRole performerRole = roleAuthorization.requirePerformerRole(server, performer);
                roleAuthorization.assertHigherAuthority(performerRole, role);
            }

            if (staffRepository.countByRoleId(role.getId()) > 0) {
                throw new ConflictException("Cannot delete role that is currently assigned to staff members");
            }

            boolean deleted = staffRoleRepository.deleteById(id);
            if (deleted) {
                invalidatePermissionState(server);
            }
            return deleted;
        });
    }

    public void reorderRoles(Server server, ReorderRolesRequest request, RoleAuthorization.PerformerAuthority performer) {
        List<ReorderRolesRequest.RoleOrderItem> items = request.roleOrder();
        if (items.isEmpty()) {
            return;
        }
        requireRankableOrders(items);

        unitOfWork.write(() -> {
            List<String> ids = items.stream().map(ReorderRolesRequest.RoleOrderItem::id).toList();
            rankLocks.lockStaff(server, performer);
            rankLocks.lockRoles(performer, ids.toArray(String[]::new));
            Map<String, StaffRole> rolesById = staffRoleRepository.findByIds(ids)
                .stream()
                .collect(Collectors.toMap(StaffRole::getId, Function.identity()));
            List<RoleReorder> knownReorders = items.stream()
                .flatMap(item -> Optional.ofNullable(rolesById.get(item.id()))
                    .map(role -> new RoleReorder(item.id(), role.getOrder(), item.order()))
                    .stream())
                .toList();

            if (!performer.superAdmin()) {
                assertCanReorder(server, performer, knownReorders);
            }

            Map<String, Integer> orderById = new LinkedHashMap<>();
            knownReorders.forEach(reorder -> orderById.put(reorder.roleId(), reorder.requestedOrder()));
            staffRoleRepository.updateOrder(orderById);
            invalidatePermissionState(server);
        });
    }

    private static void requireRankableOrders(List<ReorderRolesRequest.RoleOrderItem> items) {
        if (items.stream().anyMatch(item -> item.order() < 0 || item.order() > HIGHEST_ROLE_ORDER)) {
            throw new ValidationException("A role order must be between 0 and " + HIGHEST_ROLE_ORDER);
        }
    }

    private record RoleReorder(String roleId, int currentOrder, int requestedOrder) {
    }

    private void assertCanReorder(Server server, RoleAuthorization.PerformerAuthority performer, List<RoleReorder> reorders) {
        int performerOrder = roleAuthorization.requirePerformerRole(server, performer).getOrder();
        for (RoleReorder reorder : reorders) {
            if (reorder.currentOrder() <= performerOrder) {
                throw new ForbiddenException("You do not have authority to reorder this role");
            }
            if (reorder.requestedOrder() <= performerOrder) {
                throw new ForbiddenException("You cannot promote a role to or above your own authority level");
            }
        }
    }

    public void createDefaultRoles(Server server) {
        List<String> allPunishmentPerms = permissionService.getPunishmentPermissions(server)
            .stream()
            .map(Permission::id)
            .toList();
        String blacklistPermission = PermissionService.punishmentApplyPermissionId(DefaultPunishmentTypes.BLACKLIST_ORDINAL);
        List<String> moderatorPunishmentPerms = allPunishmentPerms.stream()
            .filter(permission -> !permission.equals(blacklistPermission))
            .toList();

        List<String> superAdminPerms = new ArrayList<>(permissionService.getGrantablePermissionIds(server));

        List<String> adminPerms = new ArrayList<>(List.of(
            PermissionService.ADMIN_SETTINGS_VIEW, PermissionService.ADMIN_STAFF_MANAGE, PermissionService.ADMIN_AUDIT_VIEW,
            PermissionService.PUNISHMENT_VIEW, PermissionService.PUNISHMENT_MODIFY,
            PermissionService.TICKET_VIEW_ALL, PermissionService.TICKET_VIEW_ALL_NOTES,
            PermissionService.TICKET_REPLY_ALL, PermissionService.TICKET_REPLY_ALL_NOTES, PermissionService.APPEAL_MODIFY, PermissionService.TICKET_CLOSE_ALL,
            PermissionService.TICKET_MANAGE,
            PermissionService.STAFF_CHAT_TOGGLE, PermissionService.STAFF_CHAT_CLEAR, PermissionService.STAFF_CHAT_SLOW,
            PermissionService.STAFF_MODACTIONS,
            PermissionService.STAFF_INTERCEPT, PermissionService.STAFF_CHATLOGS, PermissionService.STAFF_COMMANDLOGS,
            PermissionService.REPLAY_SHARE
        ));
        adminPerms.addAll(PermissionService.PLAYER_PERMISSIONS);
        adminPerms.addAll(allPunishmentPerms);

        List<String> moderatorPerms = new ArrayList<>(List.of(
            PermissionService.PUNISHMENT_VIEW, PermissionService.PUNISHMENT_MODIFY,
            PermissionService.TICKET_VIEW_ALL, PermissionService.TICKET_VIEW_ALL_NOTES,
            PermissionService.TICKET_REPLY_ALL, PermissionService.TICKET_REPLY_ALL_NOTES, PermissionService.APPEAL_MODIFY, PermissionService.TICKET_CLOSE_ALL,
            PermissionService.TICKET_MANAGE, PermissionService.STAFF_MODACTIONS,
            PermissionService.STAFF_CHATLOGS, PermissionService.STAFF_COMMANDLOGS
        ));
        moderatorPerms.addAll(PermissionService.PLAYER_PERMISSIONS);
        moderatorPerms.addAll(moderatorPunishmentPerms);

        List<String> helperPerms = new ArrayList<>(List.of(
            PermissionService.PUNISHMENT_VIEW,
            PermissionService.TICKET_VIEW_ALL, PermissionService.TICKET_VIEW_ALL_NOTES,
            PermissionService.TICKET_REPLY_ALL, PermissionService.TICKET_REPLY_ALL_NOTES, PermissionService.APPEAL_MODIFY
        ));
        helperPerms.addAll(PermissionService.PLAYER_PERMISSIONS);

        unitOfWork.write(() -> {
            Instant now = clock.instant();
            List<StaffRole> defaultRoles = List.of(
                defaultRole(RoleAuthorization.SUPER_ADMIN_ROLE_ID, RoleAuthorization.SUPER_ADMIN_ROLE_NAME, superAdminPerms, 0, now),
                defaultRole("admin", "Admin", adminPerms, 1, now),
                defaultRole("moderator", "Moderator", moderatorPerms, 2, now),
                defaultRole(HELPER_ROLE_ID, HELPER_ROLE_NAME, helperPerms, 3, now)
            );
            defaultRoles.forEach(staffRoleRepository::insertIfAbsent);
            unitOfWork.afterCommit(permissionService::evictPermissionCache);
        });
    }

    private static StaffRole defaultRole(String id, String name, List<String> permissions, int order, Instant now) {
        return StaffRole.builder()
            .id(id)
            .name(name)
            .permissions(new ArrayList<>(permissions))
            .isDefault(true)
            .order(order)
            .createdAt(now)
            .updatedAt(now)
            .build();
    }
}
