package gg.modl.minecraft.core.cache;

import gg.modl.minecraft.api.http.response.PunishmentTypesResponse;
import gg.modl.minecraft.api.http.response.SyncResponse;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class Cache {
    private static final long TEXTURE_TTL_MS = 10 * 60 * 1000;
    private static final int TEXTURE_CACHE_MAX_SIZE = 500;

    @Getter private final CachedProfileRegistry registry;
    private final Map<UUID, StaffPermissions> staffPermissionsCache = new ConcurrentHashMap<>();
    private final Object skinTextureLock = new Object();
    private final Map<UUID, CachedTexture> skinTextureCache =
            new LinkedHashMap<UUID, CachedTexture>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, CachedTexture> eldest) {
                    return size() > TEXTURE_CACHE_MAX_SIZE;
                }
            };
    @Getter private volatile @Nullable PunishmentTypesResponse cachedPunishmentTypes;
    private volatile Map<Integer, String> punishmentTypeItems = Collections.emptyMap();

    @Getter @Setter private boolean queryMojang;

    public Map<Integer, String> getPunishmentTypeItems() {
        return punishmentTypeItems;
    }

    public void setPunishmentTypeItems(@Nullable Map<Integer, String> items) {
        this.punishmentTypeItems = items == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(items));
    }

    public @Nullable CachedProfile getPlayerProfile(UUID uuid) {
        return registry.getProfile(uuid);
    }

    public void setOffline(UUID playerUuid) {
        synchronized (skinTextureLock) {
            skinTextureCache.remove(playerUuid);
        }
    }

    public void cacheSkinTexture(UUID playerUuid, @Nullable String textureValue) {
        if (textureValue == null) return;
        synchronized (skinTextureLock) {
            skinTextureCache.put(playerUuid, new CachedTexture(textureValue, System.currentTimeMillis()));
        }
    }

    public @Nullable String getSkinTexture(UUID playerUuid) {
        synchronized (skinTextureLock) {
            CachedTexture entry = skinTextureCache.get(playerUuid);
            if (entry == null) return null;
            if (entry.isExpired(System.currentTimeMillis())) {
                skinTextureCache.remove(playerUuid);
                return null;
            }
            return entry.value;
        }
    }

    @AllArgsConstructor
    private static class CachedTexture {
        final String value;
        final long cachedAt;

        private boolean isExpired(long now) {
            return now - cachedAt > TEXTURE_TTL_MS;
        }
    }

    private @Nullable StaffPermissions profileStaffPermissions(UUID playerUuid) {
        CachedProfile profile = registry.getProfile(playerUuid);
        if (profile == null) return null;
        SyncResponse.ActiveStaffMember staff = profile.getStaffMember();
        if (staff == null) return null;
        return new StaffPermissions(staff.getStaffUsername(), staff.getStaffId(), staff.getStaffRole(), staff.getPermissions());
    }

    private @Nullable StaffPermissions cachedStaffPermissions(UUID playerUuid) {
        return staffPermissionsCache.get(playerUuid);
    }

    public @Nullable String getStaffDisplayName(UUID playerUuid) {
        StaffPermissions profileView = profileStaffPermissions(playerUuid);
        if (profileView != null && !profileView.getStaffUsername().isEmpty()) return profileView.getStaffUsername();

        StaffPermissions cachedView = cachedStaffPermissions(playerUuid);
        if (cachedView != null && cachedView.getStaffUsername() != null && !cachedView.getStaffUsername().isEmpty()) return cachedView.getStaffUsername();
        return null;
    }

    public @Nullable String getStaffId(UUID playerUuid) {
        StaffPermissions profileView = profileStaffPermissions(playerUuid);
        if (profileView != null) return profileView.getStaffId();

        StaffPermissions cachedView = cachedStaffPermissions(playerUuid);
        if (cachedView != null && cachedView.getStaffId() != null) return cachedView.getStaffId();
        return null;
    }

    public String getDisplayName(UUID playerUuid, String fallback) {
        String panelName = getStaffDisplayName(playerUuid);
        return panelName != null ? panelName : fallback;
    }

    public boolean hasPermission(UUID playerUuid, String permission) {
        StaffPermissions staff = currentStaffPermissions(playerUuid);
        return staff != null && checkRoleAndPermissions(staff.getStaffRole(), staff.getPermissions(), permission);
    }

    public boolean hasAnyPermissionWithPrefix(UUID playerUuid, String prefix) {
        StaffPermissions staff = currentStaffPermissions(playerUuid);
        if (staff == null) return false;
        if (isSuperAdminRole(staff.getStaffRole())) return true;
        for (String held : staff.getPermissions())
            if (held.startsWith(prefix)) return true;
        return false;
    }

    private @Nullable StaffPermissions currentStaffPermissions(UUID playerUuid) {
        StaffPermissions cachedView = cachedStaffPermissions(playerUuid);
        return cachedView != null ? cachedView : profileStaffPermissions(playerUuid);
    }

    private static boolean isSuperAdminRole(String role) {
        return "Super Admin".equalsIgnoreCase(role);
    }

    private boolean checkRoleAndPermissions(String role, List<String> permissions, String requested) {
        return isSuperAdminRole(role) || permissions.contains(requested);
    }

    public void cacheStaffPermissions(UUID playerUuid, String staffUsername, String staffId, String staffRole, List<String> permissions) {
        List<String> cachedPermissions = Collections.unmodifiableList(new ArrayList<>(permissions));
        staffPermissionsCache.put(playerUuid, new StaffPermissions(staffUsername, staffId, staffRole, cachedPermissions));
    }

    public void removeStaffPermissions(UUID playerUuid) {
        staffPermissionsCache.remove(playerUuid);
    }

    public void retainStaff(Set<UUID> currentStaff) {
        staffPermissionsCache.keySet().retainAll(currentStaff);
        for (CachedProfile profile : registry.getAllProfiles()) {
            if (!currentStaff.contains(profile.getUuid())) profile.setStaffMember(null);
        }
    }

    public boolean isStaffMemberByPermissions(UUID playerUuid) {
        return staffPermissionsCache.containsKey(playerUuid);
    }

    public @Nullable String getStaffRole(UUID playerUuid) {
        StaffPermissions cachedView = cachedStaffPermissions(playerUuid);
        return cachedView != null ? cachedView.getStaffRole() : null;
    }

    public void endPunishment(String punishmentId) {
        for (CachedProfile profile : registry.getAllProfiles()) profile.endPunishment(punishmentId);
    }

    @Getter @RequiredArgsConstructor
    public static class StaffPermissions {
        private final String staffUsername, staffId, staffRole;
        private final List<String> permissions;
    }

    public void cachePunishmentTypes(PunishmentTypesResponse response) {
        this.cachedPunishmentTypes = response;
    }

    public void clearPunishmentTypes() {
        this.cachedPunishmentTypes = null;
    }
}
