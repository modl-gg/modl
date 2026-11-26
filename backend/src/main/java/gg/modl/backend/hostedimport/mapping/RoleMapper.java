package gg.modl.backend.hostedimport.mapping;

import static gg.modl.backend.database.jooq.Tables.STAFF_ROLES;

import gg.modl.backend.hostedimport.archive.ArchiveDocument;
import gg.modl.backend.hostedimport.mapping.ImportedIds.IdKind;
import gg.modl.backend.hostedimport.model.IssueCode;
import gg.modl.backend.role.data.Permission;
import gg.modl.backend.role.data.StaffRole;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.role.service.RoleService;
import gg.modl.backend.settings.data.PunishmentType;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class RoleMapper {
    private static final int ID_LIMIT = 64;
    private static final String RENAMED_SUFFIX = " (imported)";

    private final ImportContext context;
    private final ImportFit fit;
    private final List<String> grantable;
    private final Map<String, String> permissionNames = new HashMap<>(HostedOnlyPermissionNames.BY_ID);
    private final Set<String> droppedPermissions = new HashSet<>();
    private final Map<String, List<Integer>> ordinalsBySlug;
    private final Map<String, String> roleIdsBySource = new HashMap<>();
    private final Map<String, String> roleIdsByNameLower = new HashMap<>();
    private final List<StaffRole> roles = new ArrayList<>();

    public RoleMapper(ImportContext context, List<Permission> basePermissions, Set<String> liveRoleNamesLower) {
        this.context = context;
        this.fit = context.fit();
        List<String> ids = new ArrayList<>();
        basePermissions.forEach(permission -> permissionNames.put(permission.id(), permission.name()));
        basePermissions.stream().filter(permission -> !permission.superAdminOnly()).map(Permission::id).forEach(ids::add);
        for (PunishmentType type : context.types().types()) {
            Integer ordinal = type.getOrdinal();
            if (ordinal != null) {
                ids.add(PermissionService.punishmentApplyPermissionId(ordinal));
            }
        }
        this.grantable = List.copyOf(ids);
        this.ordinalsBySlug = slugs(context.types().types());
        Set<String> takenNames = new HashSet<>(liveRoleNamesLower);
        takenNames.add(RoleAuthorization.SUPER_ADMIN_ROLE_NAME.toLowerCase(Locale.ROOT));
        for (ArchiveDocument source : context.index().roles()) {
            importedId(source).map(sourceId -> role(source, sourceId, takenNames)).ifPresent(roles::add);
        }
    }

    public static boolean isImported(ArchiveDocument source) {
        return importedId(source).isPresent();
    }

    private static Optional<String> importedId(ArchiveDocument source) {
        return Optional.ofNullable(source.id()).filter(id -> !RoleAuthorization.SUPER_ADMIN_ROLE_ID.equals(id));
    }

    public List<StaffRole> roles() {
        return List.copyOf(roles);
    }

    public Optional<String> roleIdFor(String sourceRole) {
        String byId = roleIdsBySource.get(sourceRole);
        return Optional.ofNullable(byId != null ? byId : roleIdsByNameLower.get(sourceRole.trim().toLowerCase(Locale.ROOT)));
    }

    public Optional<StaffRole> roleFor(String sourceRole) {
        return roleIdFor(sourceRole).flatMap(id -> roles.stream().filter(role -> role.getId().equals(id)).findFirst());
    }

    private StaffRole role(ArchiveDocument source, String sourceId, Set<String> takenNames) {
        String name = fit.column(STAFF_ROLES.NAME, Objects.requireNonNullElse(source.text("name"), sourceId).trim());
        if (name == null || name.isBlank()) {
            name = sourceId;
        }
        String unique = uniqueName(name, takenNames);
        String id = context.ids().adopt(IdKind.ROLE, "", sourceId, ID_LIMIT);
        roleIdsBySource.put(sourceId, id);
        roleIdsByNameLower.put(name.toLowerCase(Locale.ROOT), id);
        Set<String> permissions = permissions(source.list("permissions"));
        permissions.addAll(PermissionService.PLAYER_PERMISSIONS);
        boolean isDefault = source.bool("isDefault");
        if (RoleService.HELPER_ROLE_ID.equals(sourceId) || (isDefault && RoleService.HELPER_ROLE_NAME.equalsIgnoreCase(name))) {
            permissions.add(PermissionService.PUNISHMENT_VIEW);
        }
        Instant created = Objects.requireNonNullElse(source.instant("createdAt"), context.exportTime());
        return StaffRole.builder()
            .id(id)
            .name(unique)
            .description(fit.textIfPresent(source.string("description")))
            .permissions(new ArrayList<>(permissions))
            .isDefault(isDefault)
            .order(Math.max(1, Objects.requireNonNullElse(source.intValue("order"), 999)))
            .createdAt(created)
            .updatedAt(Objects.requireNonNullElse(source.instant("updatedAt"), created))
            .build();
    }

    private Set<String> permissions(List<@Nullable Object> held) {
        Set<String> mapped = new LinkedHashSet<>();
        for (@Nullable Object value : held) {
            if (!(value instanceof String permission) || permission.isBlank()) {
                continue;
            }
            String id = permission.trim();
            Set<String> granted = grant(id);
            if (granted.isEmpty() && droppedPermissions.add(id)) {
                context.issues().report(IssueCode.PERMISSION_DROPPED, permissionNames.getOrDefault(id, id));
            }
            mapped.addAll(granted);
        }
        return mapped;
    }

    private Set<String> grant(String held) {
        Set<String> granted = new LinkedHashSet<>();
        if (held.startsWith(PermissionService.PUNISHMENT_APPLY_PREFIX)) {
            String slug = held.substring(PermissionService.PUNISHMENT_APPLY_PREFIX.length()).toLowerCase(Locale.ROOT);
            ordinalsBySlug.forEach((typeSlug, ordinals) -> {
                if (typeSlug.equals(slug) || typeSlug.startsWith(slug + ".")) {
                    ordinals.forEach(ordinal -> granted.add(PermissionService.punishmentApplyPermissionId(ordinal)));
                }
            });
            return granted;
        }
        if (grantable.contains(held)) {
            granted.add(held);
            PermissionService.NOT_IMPLIED_BY_PARENT.stream()
                .filter(permission -> permission.startsWith(held + ".") && grantable.contains(permission))
                .sorted()
                .forEach(granted::add);
            return granted;
        }
        grantable.stream().filter(permission -> permission.startsWith(held + ".")).forEach(granted::add);
        return granted;
    }

    private String uniqueName(String name, Set<String> takenNames) {
        String candidate = name;
        if (RoleAuthorization.SUPER_ADMIN_ROLE_NAME.equalsIgnoreCase(candidate.trim())) {
            candidate = fit.cut(RoleAuthorization.SUPER_ADMIN_ROLE_NAME + RENAMED_SUFFIX, 64);
        }
        String base = candidate;
        for (int suffix = 2; !takenNames.add(candidate.toLowerCase(Locale.ROOT)); suffix++) {
            String tail = " " + suffix;
            candidate = fit.cut(base, 64 - tail.length()) + tail;
        }
        if (!candidate.equals(name)) {
            context.issues().report(IssueCode.ROLE_RENAMED, candidate);
        }
        return candidate;
    }

    static String slug(String typeName) {
        return typeName.toLowerCase(Locale.ROOT).replace(" ", "-");
    }

    private static Map<String, List<Integer>> slugs(List<PunishmentType> types) {
        Map<String, List<Integer>> bySlug = new HashMap<>();
        for (PunishmentType type : types) {
            if (type.getName() != null && type.getOrdinal() != null) {
                bySlug.computeIfAbsent(slug(type.getName()), ignored -> new ArrayList<>()).add(type.getOrdinal());
            }
        }
        return bySlug;
    }
}
