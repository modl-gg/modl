package gg.modl.minecraft.core.impl.menus.staff;

import gg.modl.minecraft.core.connection.PanelUrl;
import gg.modl.minecraft.core.PluginServices;
import dev.simplix.cirrus.actionhandler.ActionHandlers;
import dev.simplix.cirrus.item.CirrusItem;
import dev.simplix.cirrus.item.CirrusItemType;
import dev.simplix.cirrus.model.CallResult;
import dev.simplix.cirrus.model.Click;
import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import dev.simplix.cirrus.text.CirrusChatElement;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.impl.menus.util.StaffViewer;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.impl.menus.base.BaseStaffListMenu;
import gg.modl.minecraft.core.impl.menus.util.MenuItems;
import gg.modl.minecraft.core.impl.menus.util.MenuSlots;
import gg.modl.minecraft.core.staff.action.StaffRequirements;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public class RolePermissionEditMenu extends BaseStaffListMenu<RolePermissionEditMenu.Permission> {
    @Getter @AllArgsConstructor
    public static class Permission {
        private final String node;
        @Setter private boolean enabled;
    }

    private static final String KEY = "menus.role_permissions.";

    private final RoleListMenu.Role role;
    private final RolePermissionCatalog catalog;
    private final List<Permission> allPermissions = new ArrayList<>();
    private final Set<String> enabledPermissions, originalPermissions;
    private final StaffViewer viewer;
    private final boolean editableRole;
    private final boolean hasPermission;

    public RolePermissionEditMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                                   boolean isAdmin, PanelUrl panelUrl, RoleListMenu.Role role, RolePermissionCatalog catalog,
                                   @Nullable Consumer<CirrusPlayerWrapper> backAction) {
        super(PluginServices.locale().getMessage("menus.titles.edit_role", mapOf("role", String.valueOf(role.getName()))), platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, backAction);
        this.role = role;
        this.catalog = catalog;

        this.editableRole = role.getId() != null && !role.getId().contains("super-admin");
        this.viewer = new StaffViewer(platform, viewerUuid);
        this.hasPermission = editableRole && viewer.allows(StaffRequirements.MANAGE_ROLES);

        enabledPermissions = new HashSet<>(role.getPermissions());
        originalPermissions = new HashSet<>(role.getPermissions());

        for (String node : catalog.grantableIds())
            allPermissions.add(new Permission(node, enabledPermissions.contains(node)));
    }

    @Override
    protected Map<Integer, CirrusItem> intercept(int menuSize) {
        Map<Integer, CirrusItem> items = super.intercept(menuSize);

        LocaleManager locale = localeManager();
        boolean hasChanges = !enabledPermissions.equals(originalPermissions);
        items.put(MenuSlots.FILTER_BUTTON, CirrusItem.of(
                hasChanges ? CirrusItemType.LIME_DYE : CirrusItemType.GRAY_DYE,
                CirrusChatElement.ofLegacyText(locale.getMessage(KEY + (hasChanges ? "apply_changes" : "no_changes"))),
                MenuItems.lore(locale.getMessage(KEY + (hasChanges ? "apply_changes_lore" : "no_changes_lore")))
        ).actionHandler("applyPermissions"));

        return items;
    }

    @Override
    protected Collection<Permission> items() {
        return hasPermission ? allPermissions : Collections.<Permission>emptyList();
    }

    @Override
    protected CirrusItem emptyPlaceholder() {
        if (!hasPermission)
            return MenuItems.localized(CirrusItemType.BARRIER, "menus.common.no_permission", KEY + "no_permission_lore");
        return createEmptyPlaceholder(localeManager().getMessage("menus.empty.permissions"));
    }

    @Override
    protected CirrusItem mapItem(Permission permission) {
        boolean changed = permission.isEnabled() != originalPermissions.contains(permission.getNode());
        String suffix = changed ? MenuItems.COLOR_YELLOW + " *" : "";
        String displayPrefix = catalog.hasGrantableParent(permission.getNode()) ? "  ↳ " : "";

        if (catalog.grantedByParent(permission.getNode(), enabledPermissions)) {
            CirrusItem item = CirrusItem.of(
                    CirrusItemType.LIME_DYE,
                    CirrusChatElement.ofLegacyText(MenuItems.COLOR_GREEN + displayPrefix + label(permission.getNode())),
                    MenuItems.lore(localeManager().getMessage(KEY + "auto_granted"))
            );
            item.glow();
            return item;
        }

        return CirrusItem.of(
            permission.isEnabled() ? CirrusItemType.LIME_DYE : CirrusItemType.GRAY_DYE,
            CirrusChatElement.ofLegacyText(
                (permission.isEnabled() ? MenuItems.COLOR_GREEN : MenuItems.COLOR_GRAY) + displayPrefix + label(permission.getNode()) + suffix
            ),
            MenuItems.lore(localeManager().getMessage(MenuItems.CLICK_TO_TOGGLE))
        );
    }

    private String label(String node) {
        return catalog.nameOf(node);
    }

    @Override
    protected void handleItemClick(Click click, Permission permission) {
        if (!mayEdit()) return;

        if (catalog.grantedByParent(permission.getNode(), enabledPermissions)) return;

        boolean newState = !permission.isEnabled();
        permission.setEnabled(newState);

        storeOnlyExplicitlyToggledNode(permission.getNode(), newState);

        RoleListMenu.Role localRole = new RoleListMenu.Role(
                role.getId(), role.getName(), role.getDescription(), new ArrayList<>(enabledPermissions));
        RolePermissionEditMenu newMenu = new RolePermissionEditMenu(
                platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, localRole, catalog, backAction);
        newMenu.originalPermissions.clear();
        newMenu.originalPermissions.addAll(this.originalPermissions);

        newMenu.allPermissions.clear();
        for (Permission p : this.allPermissions)
            newMenu.allPermissions.add(new Permission(p.getNode(), enabledPermissions.contains(p.getNode())));

        ActionHandlers.openMenu(newMenu).handle(click);
    }

    private void storeOnlyExplicitlyToggledNode(String node, boolean enabled) {
        if (enabled) enabledPermissions.add(node);
        else enabledPermissions.remove(node);
    }

    @Override
    protected void registerActionHandlers() {
        super.registerActionHandlers();

        registerActionHandler("applyPermissions", click -> {
            handleApply(click);
            return CallResult.DENY_GRABBING;
        });
    }

    private boolean mayEdit() {
        return editableRole && viewer.authorize(StaffRequirements.MANAGE_ROLES);
    }

    private void handleApply(Click click) {
        if (!mayEdit()) return;
        if (enabledPermissions.equals(originalPermissions)) {
            sendMessage(localeManager().getMessage(KEY + "nothing_to_save"));
            return;
        }

        sendMessage(localeManager().getMessage(KEY + "saving", mapOf("role", String.valueOf(role.getName()))));

        String actingStaffId = PluginServices.cache().getStaffId(viewerUuid);
        httpClient.updateRolePermissions(role.getId(), new ArrayList<>(enabledPermissions), actingStaffId).thenAccept(v -> {
            sendMessage(localeManager().getMessage(KEY + "saved"));
            if (backAction != null) backAction.accept(click.player());
        }).exceptionally(e -> {
            sendMessage(localeManager().getMessage(KEY + "save_failed", mapOf("error", String.valueOf(e.getMessage()))));
            return null;
        });
    }
}
