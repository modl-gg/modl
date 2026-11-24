package gg.modl.minecraft.core.staff;

import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.PanelUnavailableException;
import gg.modl.minecraft.api.http.response.StaffPermissionsResponse;
import gg.modl.minecraft.core.HttpClientHolder;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.util.Throwables;
import gg.modl.minecraft.core.util.PluginLogger;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class StaffPermissionService {
    private final HttpClientHolder httpClientHolder;
    private final Cache cache;
    private final PluginLogger logger;
    private final boolean debugMode;
    private final Runnable afterChange;

    public StaffPermissionService(HttpClientHolder httpClientHolder, Cache cache, PluginLogger logger, boolean debugMode,
                                  Runnable afterChange) {
        this.afterChange = afterChange;
        this.httpClientHolder = httpClientHolder;
        this.cache = cache;
        this.logger = logger;
        this.debugMode = debugMode;
    }

    public void reload() {
        if (debugMode) logger.info("Loading staff permissions...");
        ModlHttpClient httpClient = httpClientHolder.getClient();
        httpClient.getStaffPermissions().thenAccept(response -> {
            Set<UUID> currentStaff = new HashSet<>();
            for (StaffPermissionsResponse.StaffMember staffMember : response.getData().getStaff()) {
                if (staffMember.getMinecraftUuid() != null) {
                    try {
                        UUID uuid = UUID.fromString(staffMember.getMinecraftUuid());
                        cache.cacheStaffPermissions(uuid, staffMember.getStaffUsername(), staffMember.getStaffId(), staffMember.getStaffRole(), staffMember.getPermissions());
                        currentStaff.add(uuid);
                    } catch (IllegalArgumentException e) {
                        logger.warning("Invalid UUID for staff member: " + staffMember.getMinecraftUuid());
                    }
                }
            }
            cache.retainStaff(currentStaff);
            afterChange.run();
            if (debugMode) logger.info("Staff permissions loaded: " + currentStaff.size() + " staff members");
        }).exceptionally(throwable -> {
            Throwable cause = Throwables.rootCause(throwable);
            if (cause instanceof PanelUnavailableException) logger.warning("Failed to load staff permissions: Panel temporarily unavailable");
            else logger.warning("Failed to load staff permissions: " + throwable.getMessage());
            return null;
        });
    }
}
