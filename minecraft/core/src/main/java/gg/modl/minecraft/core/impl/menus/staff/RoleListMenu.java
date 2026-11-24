package gg.modl.minecraft.core.impl.menus.staff;

import gg.modl.minecraft.core.connection.PanelUrl;
import gg.modl.minecraft.core.PluginServices;
import dev.simplix.cirrus.actionhandler.ActionHandlers;
import dev.simplix.cirrus.item.CirrusItem;
import dev.simplix.cirrus.item.CirrusItemType;
import dev.simplix.cirrus.model.Click;
import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import dev.simplix.cirrus.text.CirrusChatElement;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.response.RolesListResponse;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.impl.menus.util.StaffViewer;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.impl.menus.base.BaseStaffListMenu;
import gg.modl.minecraft.core.impl.menus.util.MenuItems;
import gg.modl.minecraft.core.impl.menus.util.MenuAsync;
import gg.modl.minecraft.core.staff.action.StaffRequirements;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public class RoleListMenu extends BaseStaffListMenu<RoleListMenu.Role> {
    private static final String KEY = "menus.roles.";

    @Getter
    public static class Role {
        private final String id, name, description;
        private final List<String> permissions;

        public Role(String id, String name, String description, List<String> permissions) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.permissions = permissions;
        }

    }

    private final List<Role> roles = new ArrayList<>();
    private volatile RolePermissionCatalog catalog = RolePermissionCatalog.of(null);
    private final StaffViewer viewer;
    private final boolean hasPermission;
    @Getter private CompletableFuture<Void> dataFuture;

    public RoleListMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                        boolean isAdmin, PanelUrl panelUrl, @Nullable Consumer<CirrusPlayerWrapper> backAction) {
        super(PluginServices.locale().getMessage("menus.titles.edit_roles"), platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, backAction);

        this.viewer = new StaffViewer(platform, viewerUuid);
        this.hasPermission = viewer.allows(StaffRequirements.MANAGE_ROLES);

        if (hasPermission)
            this.dataFuture = fetchRoles();
        else
            this.dataFuture = CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> fetchRoles() {
        return httpClient.getRoles().thenAccept(response -> {
            if (response != null && response.getRoles() != null) {
                catalog = RolePermissionCatalog.of(response.getPermissionCatalog());
                roles.clear();
                for (RolesListResponse.RoleEntry entry : response.getRoles()) {
                    if (entry.getId() != null && entry.getId().contains("super-admin")) {
                        continue;
                    }
                    roles.add(new Role(
                            entry.getId(),
                            entry.getName(),
                            entry.getDescription(),
                            entry.getPermissions() != null ? entry.getPermissions() : Collections.emptyList()
                    ));
                }
            }
        });
    }

    @Override
    protected Collection<Role> items() {
        return hasPermission ? roles : Collections.<Role>emptyList();
    }

    @Override
    protected CirrusItem emptyPlaceholder() {
        if (!hasPermission)
            return MenuItems.localized(CirrusItemType.BARRIER, "menus.common.no_permission", KEY + "no_permission_lore");
        return createEmptyPlaceholder(localeManager().getMessage("menus.empty.roles"));
    }

    @Override
    protected CirrusItem mapItem(Role role) {
        LocaleManager locale = localeManager();

        List<String> lore = new ArrayList<>();

        if (role.getDescription() != null && !role.getDescription().isEmpty()) {
            lore.add(MenuItems.COLOR_WHITE + role.getDescription());
            lore.add("");
        }

        lore.add(locale.getMessage(KEY + "permission_count", mapOf("count", String.valueOf(role.getPermissions().size()))));
        lore.add("");
        lore.add(locale.getMessage(KEY + "click_to_edit"));

        return CirrusItem.of(
            CirrusItemType.PAPER,
            CirrusChatElement.ofLegacyText(MenuItems.COLOR_GOLD + role.getName()),
                MenuItems.lore(lore)
        );
    }

    @Override
    protected void handleItemClick(Click click, Role role) {
        if (!viewer.authorize(StaffRequirements.MANAGE_ROLES)) return;

        Cache cache = PluginServices.cache();
        if (cache != null) {
            String viewerRole = cache.getStaffRole(viewerUuid);
            if (viewerRole != null && viewerRole.equals(role.getName())) {
                sendMessage(localeManager().getMessage(KEY + "cannot_edit_own"));
                return;
            }
        }

        ActionHandlers.openMenu(
                new RolePermissionEditMenu(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, role, catalog,
                        player -> {
                            RoleListMenu m = new RoleListMenu(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, backAction);
                            MenuAsync.displayWhenLoaded(platform, m.getDataFuture(), player, m::display);
                        }))
                .handle(click);
    }
}
