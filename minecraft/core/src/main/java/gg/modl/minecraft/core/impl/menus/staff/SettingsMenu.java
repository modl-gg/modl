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
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.impl.menus.base.BaseStaffMenu;
import gg.modl.minecraft.core.impl.menus.util.MenuItems;
import gg.modl.minecraft.core.impl.menus.util.MenuSlots;
import gg.modl.minecraft.core.impl.menus.util.MenuAsync;
import gg.modl.minecraft.core.impl.menus.util.StaffViewer;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.staff.action.StaffRequirements;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public class SettingsMenu extends BaseStaffMenu {
    private static final String SETTINGS = "menus.settings.";
    private static final String INFORMATION = SETTINGS + "information.";

    private final StaffViewer viewer;
    private final boolean canReload, canManageStaff, canEditRoles;

    public SettingsMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                        boolean isAdmin, PanelUrl panelUrl, @Nullable Consumer<CirrusPlayerWrapper> backAction) {
        super(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, backAction);

        this.viewer = new StaffViewer(platform, viewerUuid);
        this.canReload = viewer.allows(StaffRequirements.RELOAD_CONFIGURATION);
        this.canManageStaff = viewer.allows(StaffRequirements.MANAGE_STAFF_MEMBERS);
        this.canEditRoles = viewer.allows(StaffRequirements.MANAGE_ROLES);

        title(PluginServices.locale().getMessage(SETTINGS + "title"));
        buildMenu();
    }

    private void buildMenu() {
        buildHeader();
        LocaleManager locale = PluginServices.locale();

        set(CirrusItem.of(
                CirrusItemType.BOOK,
                CirrusChatElement.ofLegacyText(locale.getMessage(INFORMATION + "name")),
                MenuItems.lore(informationLore(locale))
        ).slot(MenuSlots.SETTINGS_INFO));

        set(settingsItem(locale, CirrusItemType.PLAYER_HEAD, "staff_list").slot(MenuSlots.SETTINGS_STAFF_LIST)
                .actionHandler("staffMembers"));

        if (canManageStaff) {
            set(settingsItem(locale, CirrusItemType.IRON_CHESTPLATE, "manage_staff").slot(MenuSlots.SETTINGS_STAFF)
                    .actionHandler("manageStaff"));
        }

        if (canEditRoles) {
            set(settingsItem(locale, CirrusItemType.BLAZE_ROD, "edit_roles").slot(MenuSlots.SETTINGS_ROLES)
                    .actionHandler("editRoles"));
        }

        if (canReload) {
            set(settingsItem(locale, CirrusItemType.REDSTONE, "reload").slot(MenuSlots.SETTINGS_RELOAD)
                    .actionHandler("reloadModl"));
        }
    }

    private List<String> informationLore(LocaleManager locale) {
        List<String> lore = new ArrayList<>();
        String role = locale.getMessage(INFORMATION + (isAdmin ? "role_admin" : "role_staff"));
        lore.add(locale.getMessage(INFORMATION + "username", mapOf("username", viewerName)));
        lore.add(locale.getMessage(INFORMATION + "role", mapOf("role", role)));
        boolean canManageStaffOrRoles = canManageStaff || canEditRoles;
        if (canReload || canManageStaffOrRoles) {
            lore.add("");
            lore.add(locale.getMessage(INFORMATION + "permissions_header"));
            if (canReload) lore.add(locale.getMessage(INFORMATION + "permission_modify_settings"));
            if (canManageStaffOrRoles) lore.add(locale.getMessage(INFORMATION + "permission_manage_staff"));
        }
        return lore;
    }

    private static CirrusItem settingsItem(LocaleManager locale, CirrusItemType type, String key) {
        return CirrusItem.of(
                type,
                CirrusChatElement.ofLegacyText(locale.getMessage(SETTINGS + key + ".name")),
                MenuItems.lore(locale.getMessageList(SETTINGS + key + ".lore"))
        );
    }

    @Override
    protected void registerActionHandlers() {
        super.registerActionHandlers();

        Consumer<CirrusPlayerWrapper> returnToSettings = p ->
                new SettingsMenu(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, null).display(p);

        registerActionHandler("staffMembers", click -> {
            StaffMembersMenu menu = new StaffMembersMenu(platform, httpClient, viewerUuid, viewerName, isAdmin,
                    panelUrl, returnToSettings);
            MenuAsync.displayWhenLoaded(platform, menu.getDataFuture(), click.player(), menu::display);
        });

        registerActionHandler("editRoles", viewer.guard(StaffRequirements.MANAGE_ROLES, click -> {
            RoleListMenu menu = new RoleListMenu(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, returnToSettings);
            MenuAsync.displayWhenLoaded(platform, menu.getDataFuture(), click.player(), menu::display);
        }));

        registerActionHandler("reloadModl", viewer.guard(StaffRequirements.RELOAD_CONFIGURATION, this::handleReloadModl));

        registerActionHandler("manageStaff", viewer.guard(StaffRequirements.MANAGE_STAFF_MEMBERS, click -> {
            StaffListMenu menu = new StaffListMenu(platform, httpClient, viewerUuid, viewerName, isAdmin,
                    panelUrl, returnToSettings);
            MenuAsync.displayWhenLoaded(platform, menu.getDataFuture(), click.player(), menu::display);
        }));

        registerActionHandler("openSettings", click -> {});
    }

    private void handleReloadModl(Click click) {
        PluginServices.configurationReload().reload(this::sendMessage);

        ActionHandlers.openMenu(
                new SettingsMenu(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, backAction))
                .handle(click);
    }

}
