package gg.modl.minecraft.api.http;

import gg.modl.minecraft.api.http.response.RolesListResponse;
import gg.modl.minecraft.api.http.response.Staff2faTokenResponse;
import gg.modl.minecraft.api.http.response.StaffListResponse;
import gg.modl.minecraft.api.http.response.StaffPermissionsResponse;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface StaffApi {

    CompletableFuture<StaffPermissionsResponse> getStaffPermissions();

    CompletableFuture<StaffListResponse> getStaffList();

    CompletableFuture<Void> reportStaffDisconnect(String minecraftUuid, long sessionDurationMs);

    CompletableFuture<Void> updateStaffRole(String staffId, String roleName, @Nullable String actingStaffId);

    CompletableFuture<RolesListResponse> getRoles();

    CompletableFuture<Void> updateRolePermissions(String roleId, List<String> permissions, @Nullable String actingStaffId);

    CompletableFuture<Staff2faTokenResponse> generateStaff2faToken(String minecraftUuid, @Nullable String ip);
}
