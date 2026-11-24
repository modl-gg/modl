package gg.modl.minecraft.core.service;

import gg.modl.minecraft.core.config.ConfigManager.Staff2faConfig;
import gg.modl.minecraft.core.cache.CachedProfile;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

public class Staff2faService {
    public enum AuthState {
        PENDING,
        AUTHENTICATED
    }

    private final CachedProfileRegistry registry;
    @Setter private volatile Staff2faConfig config;

    public Staff2faService(CachedProfileRegistry registry, Staff2faConfig config) {
        this.registry = registry;
        this.config = config;
    }

    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }

    public boolean isAuthenticated(UUID uuid) {
        if (!isEnabled()) return true;
        CachedProfile profile = registry.getProfile(uuid);
        return profile != null && profile.getAuthState() == AuthState.AUTHENTICATED;
    }

    public void onStaffJoin(UUID uuid) {
        CachedProfile profile = registry.getProfile(uuid);
        if (profile != null) profile.setAuthState(isEnabled() ? AuthState.PENDING : AuthState.AUTHENTICATED);
    }

    public boolean verifyConnection(UUID uuid, @Nullable String approvedIp, @Nullable String connectionIp) {
        CachedProfile profile = registry.getProfile(uuid);
        if (profile == null || profile.getAuthState() != AuthState.PENDING) return false;
        if (approvedIp == null || approvedIp.isEmpty() || connectionIp == null) return false;
        if (!withoutZone(approvedIp).equals(withoutZone(connectionIp))) return false;
        profile.setAuthState(AuthState.AUTHENTICATED);
        profile.setTwoFaNotified(false);
        return true;
    }

    private static String withoutZone(String address) {
        int zone = address.indexOf('%');
        return zone < 0 ? address : address.substring(0, zone);
    }

    public boolean markNotified(UUID uuid) {
        CachedProfile profile = registry.getProfile(uuid);
        if (profile == null) return false;
        if (profile.isTwoFaNotified()) return false;
        profile.setTwoFaNotified(true);
        return true;
    }

    public @Nullable AuthState getAuthState(UUID uuid) {
        CachedProfile profile = registry.getProfile(uuid);
        return profile != null ? profile.getAuthState() : null;
    }

    public boolean isAwaitingVerification(UUID uuid) {
        return getAuthState(uuid) == AuthState.PENDING;
    }
}
