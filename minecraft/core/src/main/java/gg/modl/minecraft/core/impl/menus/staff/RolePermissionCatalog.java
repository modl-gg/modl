package gg.modl.minecraft.core.impl.menus.staff;

import gg.modl.minecraft.api.http.response.RolesListResponse.PermissionEntry;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RolePermissionCatalog {
    private static final String UNCATEGORIZED = "";

    private final Map<String, PermissionEntry> grantable;

    private RolePermissionCatalog(Map<String, PermissionEntry> grantable) {
        this.grantable = grantable;
    }

    public static RolePermissionCatalog of(@Nullable List<PermissionEntry> catalog) {
        Map<String, List<PermissionEntry>> byCategory = new LinkedHashMap<>();
        if (catalog != null) {
            for (PermissionEntry entry : catalog) {
                if (entry.getId() == null || entry.isSuperAdminOnly()) continue;
                String category = entry.getCategory() != null ? entry.getCategory() : UNCATEGORIZED;
                byCategory.computeIfAbsent(category, key -> new ArrayList<>()).add(entry);
            }
        }
        Map<String, PermissionEntry> grantable = new LinkedHashMap<>();
        for (List<PermissionEntry> entries : byCategory.values())
            for (PermissionEntry entry : entries) grantable.putIfAbsent(entry.getId(), entry);
        return new RolePermissionCatalog(Collections.unmodifiableMap(grantable));
    }

    public Collection<String> grantableIds() {
        return grantable.keySet();
    }

    public String nameOf(String id) {
        PermissionEntry entry = grantable.get(id);
        return entry != null && entry.getName() != null && !entry.getName().isEmpty() ? entry.getName() : id;
    }

    public boolean hasGrantableParent(String id) {
        return grantableParentOf(id) != null;
    }

    public boolean grantedByParent(String id, Collection<String> granted) {
        Set<String> visited = new HashSet<>();
        for (String parent = grantableParentOf(id); parent != null && visited.add(parent); parent = grantableParentOf(parent))
            if (granted.contains(parent)) return true;
        return false;
    }

    @Nullable
    private String grantableParentOf(String id) {
        PermissionEntry entry = grantable.get(id);
        if (entry == null || entry.getParentId() == null) return null;
        return grantable.containsKey(entry.getParentId()) ? entry.getParentId() : null;
    }
}
