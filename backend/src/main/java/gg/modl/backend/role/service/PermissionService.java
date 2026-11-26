package gg.modl.backend.role.service;

import gg.modl.backend.infrastructure.util.LoadOutsideCache;
import gg.modl.backend.role.data.Permission;
import gg.modl.backend.role.data.StaffRole;
import gg.modl.backend.role.repository.StaffRoleRepository;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.PunishmentTypeService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PermissionService {
    private final StaffRoleRepository staffRoleRepository;
    private final PunishmentTypeService punishmentTypeService;
    private final StaffRepository staffRepository;

    private final LoadOutsideCache<String, Boolean> permissionCache = new LoadOutsideCache<>(2000, Duration.ofMinutes(2));

    public static final String ADMIN_SETTINGS_VIEW = "admin.settings.view";
    public static final String ADMIN_SETTINGS_VIEW_PUNISHMENTS = "admin.settings.view.punishments";
    public static final String ADMIN_SETTINGS_VIEW_CONTENT = "admin.settings.view.content";
    public static final String ADMIN_SETTINGS_MODIFY = "admin.settings.modify";
    public static final String ADMIN_SETTINGS_MODIFY_PUNISHMENTS = "admin.settings.modify.punishments";
    public static final String ADMIN_SETTINGS_MODIFY_CONTENT = "admin.settings.modify.content";
    public static final String ADMIN_SETTINGS_VIEW_STORAGE = "admin.settings.view.storage";
    public static final String ADMIN_SETTINGS_MODIFY_STORAGE = "admin.settings.modify.storage";
    public static final String PUNISHMENT_APPLY_PREFIX = "punishment.apply.";
    public static final String ADMIN_STAFF_MANAGE = "admin.staff.manage";
    public static final String ADMIN_STAFF_MANAGE_MEMBERS = "admin.staff.manage.members";
    public static final String ADMIN_STAFF_MANAGE_ROLES = "admin.staff.manage.roles";
    public static final String ADMIN_AUDIT_VIEW = "admin.audit.view";
    public static final String ADMIN_AUDIT_VIEW_DASHBOARD = "admin.audit.view.dashboard";
    public static final String ADMIN_AUDIT_VIEW_ANALYTICS = "admin.audit.view.analytics";
    public static final String ADMIN_AUDIT_VIEW_LOGS = "admin.audit.view.logs";
    public static final String ADMIN_AUDIT_ROLLBACK = "admin.audit.rollback";
    public static final String PUNISHMENT_VIEW = "punishment.view";
    public static final String PUNISHMENT_MODIFY = "punishment.modify";
    public static final String PUNISHMENT_MODIFY_PARDON = "punishment.modify.pardon";
    public static final String PUNISHMENT_MODIFY_DURATION = "punishment.modify.duration";
    public static final String PUNISHMENT_MODIFY_NOTE = "punishment.modify.note";
    public static final String PUNISHMENT_MODIFY_EVIDENCE = "punishment.modify.evidence";
    public static final String PUNISHMENT_MODIFY_OPTIONS = "punishment.modify.options";
    public static final String PUNISHMENT_MODIFY_TICKETS = "punishment.modify.tickets";
    public static final String PLAYER_NOTE = "player.note";
    public static final String PLAYER_WARN = "player.warn";
    public static final String REPLAY_SHARE = "replay.share";
    public static final List<String> PLAYER_PERMISSIONS = List.of(PLAYER_NOTE, PLAYER_WARN);
    public static final String TICKET_PERMISSION_PREFIX = "ticket.";
    public static final String TICKET_VIEW_ALL = "ticket.view.all";
    public static final String TICKET_VIEW_ALL_NOTES = "ticket.view.all.notes";
    public static final String TICKET_REPLY_ALL = "ticket.reply.all";
    public static final String TICKET_REPLY_ALL_NOTES = "ticket.reply.all.notes";
    public static final Set<String> NOT_IMPLIED_BY_PARENT = Set.of(TICKET_VIEW_ALL_NOTES, TICKET_REPLY_ALL_NOTES);
    public static final String APPEAL_MODIFY = "appeal.modify";
    public static final String TICKET_CLOSE_ALL = "ticket.close.all";
    public static final String TICKET_MANAGE = "ticket.manage";
    public static final String TICKET_MANAGE_TAGS = "ticket.manage.tags";
    public static final String TICKET_MANAGE_HIDE = "ticket.manage.hide";
    public static final String STAFF_CHAT_TOGGLE = "staff.chat.toggle";
    public static final String STAFF_CHAT_CLEAR = "staff.chat.clear";
    public static final String STAFF_CHAT_SLOW = "staff.chat.slow";
    public static final String STAFF_MODACTIONS = "staff.modactions";
    public static final String STAFF_INTERCEPT = "staff.intercept";
    public static final String STAFF_CHATLOGS = "staff.chatlogs";
    public static final String STAFF_COMMANDLOGS = "staff.commandlogs";

    private static final List<Permission> BASE_PERMISSIONS = List.of(
        new Permission(ADMIN_SETTINGS_VIEW, "View Settings", "View all system settings (includes all sub-permissions)", "admin"),
        new Permission(ADMIN_SETTINGS_VIEW_PUNISHMENTS, "View Punishments Config", "View punishment type configuration", "admin", ADMIN_SETTINGS_VIEW),
        new Permission(ADMIN_SETTINGS_VIEW_CONTENT, "View Content", "View homepage cards, knowledgebase, media", "admin", ADMIN_SETTINGS_VIEW),
        new Permission(ADMIN_SETTINGS_VIEW_STORAGE, "View Storage", "View storage configuration", "admin", ADMIN_SETTINGS_VIEW),
        new Permission(ADMIN_SETTINGS_MODIFY, "Modify Settings", "Full control over system settings (includes all sub-permissions)", "admin"),
        new Permission(ADMIN_SETTINGS_MODIFY_PUNISHMENTS, "Modify Punishments Config", "Create/edit/delete punishment types", "admin",
            ADMIN_SETTINGS_MODIFY),
        new Permission(ADMIN_SETTINGS_MODIFY_CONTENT, "Modify Content", "Edit homepage cards, knowledgebase, media", "admin", ADMIN_SETTINGS_MODIFY),
        new Permission(ADMIN_SETTINGS_MODIFY_STORAGE, "Modify Storage", "Configure storage backends and limits", "admin", ADMIN_SETTINGS_MODIFY),
        new Permission(ADMIN_STAFF_MANAGE, "Manage Staff", "Full staff management (includes all sub-permissions)", "admin"),
        new Permission(ADMIN_STAFF_MANAGE_MEMBERS, "Manage Members", "Invite, remove, and reassign staff", "admin", ADMIN_STAFF_MANAGE),
        new Permission(ADMIN_STAFF_MANAGE_ROLES, "Manage Roles", "Create/edit/delete roles and permissions", "admin", ADMIN_STAFF_MANAGE),
        new Permission(ADMIN_AUDIT_VIEW, "View Audit", "Full audit access (includes all sub-permissions)", "admin"),
        new Permission(ADMIN_AUDIT_VIEW_DASHBOARD, "View Dashboard", "View dashboard statistics", "admin", ADMIN_AUDIT_VIEW),
        new Permission(ADMIN_AUDIT_VIEW_ANALYTICS, "View Analytics", "View player and ticket analytics", "admin", ADMIN_AUDIT_VIEW),
        new Permission(ADMIN_AUDIT_VIEW_LOGS, "View Logs", "View audit trail of staff actions", "admin", ADMIN_AUDIT_VIEW),
        new Permission(ADMIN_AUDIT_ROLLBACK, "Rollback Audit Actions", "Roll back punishments and perform destructive bulk audit operations", "admin", null, true),
        new Permission(PUNISHMENT_VIEW, "View Punishments", "View player profiles, punishments, and linked accounts", "punishment"),
        new Permission(PUNISHMENT_MODIFY, "Modify Punishments", "Full control over existing punishments (includes all sub-permissions)", "punishment"),
        new Permission(PUNISHMENT_MODIFY_PARDON, "Pardon Punishments", "Pardon punishments and clear associated points", "punishment", PUNISHMENT_MODIFY),
        new Permission(PUNISHMENT_MODIFY_DURATION, "Modify Duration", "Change punishment duration", "punishment", PUNISHMENT_MODIFY),
        new Permission(PUNISHMENT_MODIFY_NOTE, "Add Notes", "Add staff notes to punishments", "punishment", PUNISHMENT_MODIFY),
        new Permission(PUNISHMENT_MODIFY_EVIDENCE, "Manage Evidence", "Add and view evidence on punishments", "punishment", PUNISHMENT_MODIFY),
        new Permission(PUNISHMENT_MODIFY_OPTIONS, "Toggle Options", "Toggle alt-blocking and stat-wipe options", "punishment", PUNISHMENT_MODIFY),
        new Permission(PUNISHMENT_MODIFY_TICKETS, "Manage Linked Tickets", "Attach and detach tickets on punishments", "punishment", PUNISHMENT_MODIFY),
        new Permission(PLAYER_NOTE, "Add Player Notes", "Add staff notes to player profiles", "player"),
        new Permission(PLAYER_WARN, "Warn Players", "Warn players with /warn", "player"),
        new Permission(REPLAY_SHARE, "Share Replays", "Create and revoke public links to replays", "player"),
        new Permission(STAFF_CHAT_TOGGLE, "Toggle Chat", "Toggle server chat on/off", "staff"),
        new Permission(STAFF_CHAT_CLEAR, "Clear Chat", "Clear server chat", "staff"),
        new Permission(STAFF_CHAT_SLOW, "Slow Chat", "Set slow mode on server chat", "staff"),
        new Permission(STAFF_MODACTIONS, "Moderation Actions", "Staff mode, vanish, freeze, and target players", "staff"),
        new Permission(STAFF_INTERCEPT, "Intercept Chat", "Intercept and view all network chat", "staff"),
        new Permission(STAFF_CHATLOGS, "Chat Logs", "View player chat history", "staff"),
        new Permission(STAFF_COMMANDLOGS, "Command Logs", "View player command history", "staff"),
        new Permission(TICKET_VIEW_ALL, "View All Tickets", "View all tickets, without their internal staff notes", "ticket"),
        new Permission(TICKET_VIEW_ALL_NOTES, "View Staff Notes", "View internal staff notes on tickets (granted on its own)", "ticket"),
        new Permission(TICKET_REPLY_ALL, "Reply to All Tickets", "Reply to all ticket types, without adding internal staff notes", "ticket"),
        new Permission(TICKET_REPLY_ALL_NOTES, "Add Staff Notes", "Add internal staff notes to tickets (granted on its own)", "ticket"),
        new Permission(APPEAL_MODIFY, "Modify Appeals", "Reply to and update appeals", "ticket"),
        new Permission(TICKET_CLOSE_ALL, "Close/Reopen All Tickets", "Close and reopen all ticket types (includes all sub-permissions)", "ticket"),
        new Permission(TICKET_MANAGE, "Manage Tickets", "Advanced ticket management (includes all sub-permissions)", "ticket"),
        new Permission(TICKET_MANAGE_TAGS, "Manage Tags", "Add and remove tags from tickets", "ticket", TICKET_MANAGE),
        new Permission(TICKET_MANAGE_HIDE, "Hide Tickets", "Hide tickets from public view", "ticket", TICKET_MANAGE)
    );

    private static final Set<String> SUPER_ADMIN_ONLY_PERMISSION_IDS = BASE_PERMISSIONS.stream()
        .filter(Permission::superAdminOnly)
        .map(Permission::id)
        .collect(Collectors.toUnmodifiableSet());

    private static final Map<String, String> PERMISSION_CATEGORIES = Map.of(
        "punishment", "Punishment Permissions",
        "player", "Player Permissions",
        "ticket", "Ticket Permissions",
        "admin", "Administrative Permissions",
        "staff", "Staff Tool Permissions"
    );

    public List<Permission> getBasePermissions() {
        return BASE_PERMISSIONS;
    }

    public Map<String, String> getPermissionCategories() {
        return PERMISSION_CATEGORIES;
    }

    public List<String> getAllPermissionIds(Server server) {
        return getAllPermissions(server).stream().map(Permission::id).toList();
    }

    public List<Permission> getAllPermissions(Server server) {
        List<Permission> all = new ArrayList<>(BASE_PERMISSIONS);
        all.addAll(getPunishmentPermissions(server));
        return all;
    }

    public List<Permission> getGrantablePermissions(Server server) {
        return getAllPermissions(server).stream()
            .filter(permission -> !permission.superAdminOnly())
            .toList();
    }

    public List<String> getGrantablePermissionIds(Server server) {
        return getGrantablePermissions(server).stream().map(Permission::id).toList();
    }

    public static boolean isSuperAdminOnly(String permissionId) {
        return SUPER_ADMIN_ONLY_PERMISSION_IDS.contains(permissionId);
    }

    public static Set<String> superAdminOnlyPermissionIds() {
        return SUPER_ADMIN_ONLY_PERMISSION_IDS;
    }

    public static List<String> grantedPermissionIds(StaffRole role) {
        List<String> permissions = role.getPermissions();
        if (permissions == null) {
            return List.of();
        }
        return permissions.stream().filter(permissionId -> !isSuperAdminOnly(permissionId)).toList();
    }

    public List<Permission> getPunishmentPermissions(Server server) {
        List<PunishmentType> punishmentTypes = punishmentTypeService.getPunishmentTypes(server);
        List<Permission> permissions = new ArrayList<>();

        punishmentTypes.forEach(type -> {
            if (type.getOrdinal() == null) {
                return;
            }
            String permId = punishmentApplyPermissionId(type.getOrdinal());
            permissions.add(new Permission(
                permId,
                "Apply " + type.getName(),
                "Permission to apply " + type.getName() + " punishments",
                "punishment"
            ));
        });

        return permissions;
    }

    public static String punishmentApplyPermissionId(int typeOrdinal) {
        return PUNISHMENT_APPLY_PREFIX + typeOrdinal;
    }

    public boolean hasPermission(Server server, @Nullable String roleId, String permission) {
        if (roleId == null || roleId.isBlank()) {
            return false;
        }

        String trimmedRole = roleId.trim();
        String cacheKey = server.getId() + ":" + trimmedRole + ":" + permission;
        return permissionCache.get(cacheKey, () -> computeHasPermission(trimmedRole, permission));
    }

    private boolean computeHasPermission(String roleId, String permission) {
        StaffRole role = staffRoleRepository.findById(roleId).orElse(null);
        if (role == null) {
            return false;
        }
        if (isSuperAdminOnly(permission) && !RoleAuthorization.isSuperAdminRole(role)) {
            return false;
        }
        return RoleAuthorization.roleGrants(role, permission);
    }

    public boolean hasAnyPermissionWithPrefix(Server server, @Nullable String roleId, String prefix) {
        if (roleId == null || roleId.isBlank()) {
            return false;
        }

        String trimmedRole = roleId.trim();
        String cacheKey = server.getId() + ":" + trimmedRole + ":prefix:" + prefix;
        return permissionCache.get(cacheKey, () -> computeHasPermissionWithPrefix(trimmedRole, prefix));
    }

    private boolean computeHasPermissionWithPrefix(String roleId, String prefix) {
        StaffRole role = staffRoleRepository.findById(roleId).orElse(null);
        if (role == null) {
            return false;
        }
        if (RoleAuthorization.isSuperAdminRole(role)) {
            return true;
        }
        return role.getPermissions().stream().anyMatch(p -> p.startsWith(prefix));
    }

    public void evictPermissionCache() {
        permissionCache.invalidateAll();
    }

    public Optional<StaffRole> getRoleByName(Server server, String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return Optional.empty();
        }

        return staffRoleRepository.findByNameIgnoreCase(roleName.trim());
    }

    public Optional<StaffRole> getRoleById(Server server, @Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return Optional.empty();
        }

        return staffRoleRepository.findById(roleId.trim());
    }

    public Map<String, StaffRole> getRolesByIds(Server server, Collection<String> roleIds) {
        Set<String> ids = roleIds.stream()
            .filter(id -> id != null && !id.isBlank())
            .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return staffRoleRepository.findByIds(ids).stream()
            .collect(Collectors.toMap(StaffRole::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    public Map<String, String> resolveRoleNames(Server server, Collection<String> roleIds) {
        Map<String, String> names = new LinkedHashMap<>();
        getRolesByIds(server, roleIds).forEach((id, role) -> names.put(id, role.getName()));
        return names;
    }

    public String assignedRoleName(Server server, Staff staff) {
        return resolveRoleName(server, staff.getRoleId());
    }

    public String effectiveRoleName(Server server, Staff staff) {
        return resolveRoleName(server, RoleAuthorization.effectiveRoleId(server, staff));
    }

    public String resolveRoleName(Server server, @Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return "";
        }
        return getRoleById(server, roleId).map(StaffRole::getName).orElse(roleId);
    }

    public boolean isSuperAdmin(Server server, String staffEmail) {
        return RoleAuthorization.isSuperAdminEmail(server, staffEmail);
    }

    public boolean isAuthorizedEmail(Server server, String email) {
        if (isSuperAdmin(server, email)) {
            return true;
        }
        return staffRepository.existsByEmail(email);
    }
}
