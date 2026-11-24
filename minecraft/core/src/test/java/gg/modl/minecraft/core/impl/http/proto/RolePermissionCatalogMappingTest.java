package gg.modl.minecraft.core.impl.http.proto;

import com.google.gson.Gson;
import gg.modl.minecraft.api.http.response.RolesListResponse;
import gg.modl.minecraft.api.http.response.RolesListResponse.PermissionEntry;
import gg.modl.proto.modl.v1.MinecraftPermission;
import gg.modl.proto.modl.v1.MinecraftRoleListResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RolePermissionCatalogMappingTest {
    @Test
    void theV3RoleListCarriesTheCatalogWithParentLinksAndSuperAdminFlags() {
        RolesListResponse response = StaffRoleProtoMapper.toRolesListResponse(MinecraftRoleListResponse.newBuilder()
                .setStatus(200)
                .addPermissionCatalog(MinecraftPermission.newBuilder()
                        .setId("punishment.modify.pardon").setName("Pardon Punishments").setCategory("punishment")
                        .setParentId("punishment.modify"))
                .addPermissionCatalog(MinecraftPermission.newBuilder()
                        .setId("ticket.view.all.notes").setName("View Staff Notes").setCategory("ticket"))
                .addPermissionCatalog(MinecraftPermission.newBuilder()
                        .setId("admin.audit.rollback").setName("Rollback").setCategory("admin").setSuperAdminOnly(true))
                .build());

        PermissionEntry pardon = response.getPermissionCatalog().get(0);
        assertEquals("punishment.modify.pardon", pardon.getId());
        assertEquals("Pardon Punishments", pardon.getName());
        assertEquals("punishment", pardon.getCategory());
        assertEquals("punishment.modify", pardon.getParentId());
        assertFalse(pardon.isSuperAdminOnly());
        assertNull(response.getPermissionCatalog().get(1).getParentId());
        assertTrue(response.getPermissionCatalog().get(2).isSuperAdminOnly());
    }

    @Test
    void theV2JsonRoleListCarriesTheSameCatalog() {
        RolesListResponse response = new Gson().fromJson("{\"status\":200,\"roles\":[],\"permissionCatalog\":["
                + "{\"id\":\"punishment.modify.pardon\",\"name\":\"Pardon Punishments\",\"category\":\"punishment\","
                + "\"parentId\":\"punishment.modify\",\"superAdminOnly\":false},"
                + "{\"id\":\"admin.audit.rollback\",\"name\":\"Rollback\",\"category\":\"admin\",\"parentId\":null,"
                + "\"superAdminOnly\":true}]}", RolesListResponse.class);

        assertEquals("punishment.modify", response.getPermissionCatalog().get(0).getParentId());
        assertEquals("Pardon Punishments", response.getPermissionCatalog().get(0).getName());
        assertNull(response.getPermissionCatalog().get(1).getParentId());
        assertTrue(response.getPermissionCatalog().get(1).isSuperAdminOnly());
    }
}
