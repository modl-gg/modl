package gg.modl.minecraft.core.impl.menus.staff;

import gg.modl.minecraft.core.connection.PanelUrl;
import gg.modl.minecraft.core.PluginServices;
import dev.simplix.cirrus.actionhandler.ActionHandlers;
import dev.simplix.cirrus.item.CirrusItem;
import dev.simplix.cirrus.item.CirrusItemType;
import dev.simplix.cirrus.model.CirrusClickType;
import dev.simplix.cirrus.model.Click;
import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import dev.simplix.cirrus.text.CirrusChatElement;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.response.RolesListResponse;
import gg.modl.minecraft.api.http.response.StaffListResponse;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.impl.menus.base.BaseStaffListMenu;
import gg.modl.minecraft.core.impl.menus.util.MenuItems;
import gg.modl.minecraft.core.impl.menus.util.SkinTextureCache;
import gg.modl.minecraft.core.impl.menus.util.StaffViewer;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.staff.action.StaffRequirements;
import gg.modl.minecraft.core.util.Permissions;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public class StaffListMenu extends BaseStaffListMenu<StaffListMenu.StaffMember> {
    @Getter
    public static class StaffMember {
        private final String id;
        private final @Nullable UUID uuid;
        private final String username, currentRole;

        public StaffMember(String id, @Nullable UUID uuid, String username, String currentRole) {
            this.id = id;
            this.uuid = uuid;
            this.username = username;
            this.currentRole = currentRole;
        }

    }

    private static final String SUPER_ADMIN_ROLE = "Super Admin";
    private static final String KEY = "menus.staff_list.";

    private List<StaffMember> staffMembers = new ArrayList<>();
    private List<String> availableRoles = new ArrayList<>();
    private Map<String, Integer> roleOrders = new HashMap<>();
    private @Nullable String viewerRole;
    private final Map<String, String> selectedRoles = new HashMap<>();
    private final StaffViewer viewer;
    private final boolean hasPermission;
    @Getter private CompletableFuture<Void> dataFuture;

    public StaffListMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                         boolean isAdmin, PanelUrl panelUrl, @Nullable Consumer<CirrusPlayerWrapper> backAction) {
        super(PluginServices.locale().getMessage("menus.titles.manage_staff"), platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, backAction);

        this.viewer = new StaffViewer(platform, viewerUuid);
        this.hasPermission = viewer.allows(StaffRequirements.MANAGE_STAFF_MEMBERS);

        if (hasPermission)
            this.dataFuture = fetchStaffAndRoles();
        else
            this.dataFuture = CompletableFuture.completedFuture(null);
    }

    private StaffListMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                          boolean isAdmin, PanelUrl panelUrl, @Nullable Consumer<CirrusPlayerWrapper> backAction,
                          List<StaffMember> existingStaff, List<String> existingRoles,
                          Map<String, Integer> existingRoleOrders, @Nullable String viewerRole,
                          Map<String, String> existingSelections) {
        super(PluginServices.locale().getMessage("menus.titles.manage_staff"), platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, backAction);

        this.viewer = new StaffViewer(platform, viewerUuid);
        this.hasPermission = viewer.allows(StaffRequirements.MANAGE_STAFF_MEMBERS);

        if (existingStaff != null) this.staffMembers = new ArrayList<>(existingStaff);
        if (existingRoles != null) this.availableRoles = new ArrayList<>(existingRoles);
        if (existingRoleOrders != null) this.roleOrders = new HashMap<>(existingRoleOrders);
        this.viewerRole = viewerRole;
        if (existingSelections != null) this.selectedRoles.putAll(existingSelections);
        this.dataFuture = CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> fetchStaffAndRoles() {
        CompletableFuture<Void> rolesFuture = httpClient.getRoles().thenAccept(response -> {
            if (response == null || !response.isSuccess() || response.getRoles() == null) {
                throw new IllegalStateException("Failed to load staff roles");
            }

            availableRoles.clear();
            roleOrders.clear();
            for (RolesListResponse.RoleEntry role : response.getRoles()) {
                roleOrders.put(role.getName(), role.getOrder());
                if (!SUPER_ADMIN_ROLE.equals(role.getName()))
                    availableRoles.add(role.getName());
            }
        });

        CompletableFuture<Void> staffFuture = httpClient.getStaffList().thenAccept(response -> {
            if (response == null || !response.isSuccess() || response.getStaff() == null) {
                throw new IllegalStateException("Failed to load staff members");
            }

            staffMembers.clear();
            for (StaffListResponse.StaffEntry entry : response.getStaff()) {
                UUID uuid = null;
                if (entry.getMinecraftUuid() != null) {
                    try {
                        uuid = UUID.fromString(entry.getMinecraftUuid());
                    } catch (Exception ignored) {}
                }
                String displayName = entry.getMinecraftUsername() != null ? entry.getMinecraftUsername() : entry.getUsername();
                staffMembers.add(new StaffMember(entry.getId(), uuid, displayName, entry.getRole()));

                if (uuid != null && uuid.equals(viewerUuid)) {
                    viewerRole = entry.getRole();
                }
            }

            for (StaffMember staff : staffMembers) {
                SkinTextureCache.ensureCached(staff.getUuid());
            }
        });

        return CompletableFuture.allOf(rolesFuture, staffFuture);
    }

    private int getRoleOrder(String roleName) {
        return roleOrders.getOrDefault(roleName, Integer.MAX_VALUE);
    }

    private boolean isSuperAdmin(StaffMember staff) {
        return SUPER_ADMIN_ROLE.equals(staff.getCurrentRole());
    }

    private boolean isViewerSelf(StaffMember staff) {
        return viewerUuid.equals(staff.getUuid());
    }

    private boolean canModify(StaffMember staff) {
        if (isSuperAdmin(staff)) return false;
        if (isViewerSelf(staff)) return false;
        return viewerRoleOrder() < getRoleOrder(staff.getCurrentRole());
    }

    private int viewerRoleOrder() {
        Cache cache = PluginServices.cache();
        if (cache.hasPermission(viewerUuid, Permissions.ADMIN)) return Integer.MIN_VALUE;
        String role = viewerRole;
        return role == null ? Integer.MAX_VALUE : getRoleOrder(role);
    }

    @Override
    protected Collection<StaffMember> items() {
        return hasPermission ? staffMembers : Collections.<StaffMember>emptyList();
    }

    @Override
    protected CirrusItem emptyPlaceholder() {
        if (!hasPermission)
            return MenuItems.localized(CirrusItemType.BARRIER, "menus.common.no_permission", KEY + "no_permission_lore");
        return createEmptyPlaceholder(localeManager().getMessage("menus.empty.staff_members"));
    }

    @Override
    protected CirrusItem mapItem(StaffMember staff) {
        boolean canMod = canModify(staff);
        List<String> lore = canMod ? buildSelectableStaffLore(staff) : buildLockedStaffLore(staff);

        CirrusItem headItem = CirrusItem.of(
                CirrusItemType.PLAYER_HEAD,
                CirrusChatElement.ofLegacyText(
                        (canMod ? MenuItems.COLOR_GOLD : MenuItems.COLOR_GRAY) + staff.getUsername()),
                MenuItems.lore(lore)
        );

        headItem = SkinTextureCache.applyCachedOrFetch(headItem, staff.getUuid());

        return headItem;
    }

    private List<String> buildLockedStaffLore(StaffMember staff) {
        LocaleManager locale = localeManager();
        List<String> lore = new ArrayList<>();
        lore.add(locale.getMessage(KEY + "role", mapOf("role", String.valueOf(staff.getCurrentRole()))));
        lore.add("");
        if (isSuperAdmin(staff)) {
            lore.add(locale.getMessage(KEY + "super_admin_locked"));
        } else if (isViewerSelf(staff)) {
            lore.add(locale.getMessage(KEY + "self_locked"));
        } else {
            lore.addAll(locale.getMessageList(KEY + "rank_locked"));
        }
        return lore;
    }

    private List<String> buildSelectableStaffLore(StaffMember staff) {
        LocaleManager locale = localeManager();
        List<String> lore = new ArrayList<>();
        String selectedRole = selectedRoles.getOrDefault(staff.getId(), staff.getCurrentRole());

        lore.add(locale.getMessage(KEY + "roles_header"));
        for (String role : availableRoles) {
            String lineKey;
            if (role.equals(staff.getCurrentRole())) lineKey = "role_current";
            else if (role.equals(selectedRole)) lineKey = "role_selected";
            else lineKey = "role_option";
            lore.add(locale.getMessage(KEY + lineKey, mapOf("role", role)));
        }
        lore.add("");
        lore.add(locale.getMessage(KEY + "cycle_roles"));
        lore.add(locale.getMessage(KEY + "apply_role"));
        return lore;
    }

    @Override
    protected void handleItemClick(Click click, StaffMember staff) {
        if (!viewer.authorize(StaffRequirements.MANAGE_STAFF_MEMBERS)) return;

        if (!canModify(staff)) {
            return;
        }

        if (click.clickType().equals(CirrusClickType.RIGHT_CLICK))
            cycleRole(click, staff);
        else
            applyRole(click, staff);
    }

    private void cycleRole(Click click, StaffMember staff) {
        if (availableRoles.isEmpty()) {
            sendMessage(localeManager().getMessage(KEY + "no_roles"));
            return;
        }

        String currentSelected = selectedRoles.getOrDefault(staff.getId(), staff.getCurrentRole());
        int currentIndex = availableRoles.indexOf(currentSelected);
        int nextIndex = (currentIndex + 1) % availableRoles.size();
        String nextRole = availableRoles.get(nextIndex);

        selectedRoles.put(staff.getId(), nextRole);
        sendMessage(localeManager().getMessage(KEY + "role_chosen", mapOf("role", nextRole)));

        StaffListMenu newMenu = new StaffListMenu(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl,
                backAction, staffMembers, availableRoles, roleOrders, viewerRole, selectedRoles);
        ActionHandlers.openMenu(newMenu).handle(click);
    }

    private void applyRole(Click click, StaffMember staff) {
        String selectedRole = selectedRoles.getOrDefault(staff.getId(), staff.getCurrentRole());

        if (selectedRole.equals(staff.getCurrentRole())) {
            sendMessage(localeManager().getMessage(KEY + "already_current", mapOf("player", String.valueOf(staff.getUsername()))));
            return;
        }

        sendMessage(localeManager().getMessage(KEY + "applying", mapOf("role", selectedRole, "player", String.valueOf(staff.getUsername()))));

        String actingStaffId = PluginServices.cache().getStaffId(viewerUuid);
        httpClient.updateStaffRole(staff.getId(), selectedRole, actingStaffId).thenAccept(v -> {
            sendMessage(localeManager().getMessage(KEY + "updated"));
            selectedRoles.remove(staff.getId());

            httpClient.getStaffPermissions().exceptionally(e -> null);

            StaffListMenu newMenu = new StaffListMenu(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, backAction);
            ActionHandlers.openMenu(newMenu).handle(click);
        }).exceptionally(e -> {
            sendMessage(localeManager().getMessage(KEY + "update_failed", mapOf("error", String.valueOf(e.getMessage()))));
            return null;
        });
    }
}
