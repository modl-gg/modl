package gg.modl.minecraft.core.impl.menus.staff;

import dev.simplix.cirrus.item.CirrusItemType;
import gg.modl.minecraft.api.http.response.RolesListResponse.PermissionEntry;
import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import gg.modl.minecraft.core.connection.PanelUrl;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.support.FakeModlHttpClient;
import gg.modl.minecraft.core.support.FakePlatform;
import gg.modl.minecraft.core.support.TestPluginServices;
import gg.modl.minecraft.core.util.Permissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RolePermissionEditMenuTest {
    private final UUID viewer = UUID.randomUUID();
    private final Cache cache = new Cache(new CachedProfileRegistry());
    private final LocaleManager locale = new LocaleManager();
    private final PluginServices services = TestPluginServices.install(cache, locale,
            TestPluginServices.runningGate(cache, locale), null);

    @AfterEach
    void uninstall() {
        PluginServices.uninstall(services);
    }

    @Test
    void theEditorOffersExactlyTheGrantablePermissionsOfTheBackendCatalogGroupedByCategory() {
        grant(Permissions.STAFF_MANAGE_ROLES);
        RolePermissionCatalog catalog = RolePermissionCatalog.of(Arrays.asList(
                entry("punishment.modify", "punishment", null, false),
                entry("ticket.view.all", "ticket", null, false),
                entry("punishment.modify.pardon", "punishment", "punishment.modify", false),
                entry("admin.audit.rollback", "admin", null, true),
                entry("punishment.apply.7", "punishment", null, false)));

        assertEquals(Arrays.asList("punishment.modify", "punishment.modify.pardon", "punishment.apply.7", "ticket.view.all"),
                nodes(menu(catalog)));
    }

    @Test
    void aPermissionTheBackendAddsIsOfferedWithoutAPluginChange() {
        grant(Permissions.STAFF_MANAGE_ROLES);

        List<String> nodes = nodes(menu(RolePermissionCatalog.of(Collections.singletonList(
                entry("future.permission", "staff", null, false)))));

        assertEquals(Collections.singletonList("future.permission"), nodes);
    }

    @Test
    void withoutACatalogNothingIsOffered() {
        grant(Permissions.STAFF_MANAGE_ROLES);

        RolePermissionEditMenu menu = menu(RolePermissionCatalog.of(null));

        assertEquals(Collections.emptyList(), nodes(menu));
        assertEquals(CirrusItemType.GRAY_STAINED_GLASS_PANE, menu.emptyPlaceholder().itemType());
    }

    @Test
    void staffWithoutRoleManagementSeeNoEditablePermissions() {
        grant(Permissions.SETTINGS_MODIFY);

        RolePermissionEditMenu menu = menu(RolePermissionCatalog.of(Collections.singletonList(
                entry("punishment.view", "punishment", null, false))));

        assertEquals(Collections.emptyList(), nodes(menu));
        assertEquals(CirrusItemType.BARRIER, menu.emptyPlaceholder().itemType());
    }

    static PermissionEntry entry(String id, String category, String parentId, boolean superAdminOnly) {
        return new PermissionEntry(id, "Name of " + id, category, parentId, superAdminOnly);
    }

    private RolePermissionEditMenu menu(RolePermissionCatalog catalog) {
        return new RolePermissionEditMenu(new FakePlatform(), new FakeModlHttpClient(), viewer, "Admin", false,
                PanelUrl.of("https://panel.example.com"),
                new RoleListMenu.Role("moderator", "Moderator", "", new ArrayList<>()), catalog, null);
    }

    private void grant(String permission) {
        cache.cacheStaffPermissions(viewer, "Admin", "staff-1", "Admin", Collections.singletonList(permission));
    }

    private static List<String> nodes(RolePermissionEditMenu menu) {
        List<String> nodes = new ArrayList<>();
        for (RolePermissionEditMenu.Permission permission : menu.items()) nodes.add(permission.getNode());
        return nodes;
    }
}
