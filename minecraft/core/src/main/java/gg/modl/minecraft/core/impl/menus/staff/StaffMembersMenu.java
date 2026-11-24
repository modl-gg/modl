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
import gg.modl.minecraft.api.http.response.StaffListResponse;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.cache.CachedProfile;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.impl.menus.base.BaseStaffListMenu;
import gg.modl.minecraft.core.impl.menus.util.MenuItems;
import gg.modl.minecraft.core.impl.menus.util.Presence;
import gg.modl.minecraft.core.impl.menus.util.OptionCycle;
import gg.modl.minecraft.core.impl.menus.util.MenuSlots;
import gg.modl.minecraft.core.impl.menus.util.SkinTextureCache;
import gg.modl.minecraft.core.locale.LocaleManager;
import lombok.Getter;
import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public class StaffMembersMenu extends BaseStaffListMenu<StaffMembersMenu.StaffMemberEntry> {
    private static final String FILTER_ONLINE = "online", FILTER_OFFLINE = "offline", FILTER_ALL = "all";
    private static final OptionCycle FILTERS = OptionCycle.of("menus.staff_members.filters.", FILTER_ONLINE, FILTER_OFFLINE, FILTER_ALL);

    @Value
    public static class StaffMemberEntry {
        String id;
        String panelName;
        String role;
        @Nullable UUID minecraftUuid;
        String minecraftUsername;
        String lastServer;
        @Nullable Instant lastSeen;
        Long totalPlaytimeMs;
        int punishmentsIssuedCount;
        boolean online;
        long sessionDuration;
    }

    private List<StaffMemberEntry> staffMembers = new ArrayList<>();
    private String currentFilter;
    @Getter private CompletableFuture<Void> dataFuture;

    public StaffMembersMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                            boolean isAdmin, PanelUrl panelUrl, @Nullable Consumer<CirrusPlayerWrapper> backAction) {
        this(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, backAction, FILTERS.first(), null);
    }

    public StaffMembersMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                            boolean isAdmin, PanelUrl panelUrl, @Nullable Consumer<CirrusPlayerWrapper> backAction,
                            String filter, @Nullable List<StaffMemberEntry> existingMembers) {
        super(PluginServices.locale().getMessage("menus.titles.staff_members"), platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, backAction);
        this.currentFilter = filter;

        if (existingMembers != null) {
            this.staffMembers = new ArrayList<>(existingMembers);
            this.dataFuture = CompletableFuture.completedFuture(null);
        } else
            this.dataFuture = fetchStaffMembers();
    }

    private CompletableFuture<Void> fetchStaffMembers() {
        Cache cache = PluginServices.cache();
        return httpClient.getStaffList().thenAccept(response -> {
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

                CachedProfile staffProfile = uuid != null && cache != null ? cache.getPlayerProfile(uuid) : null;
                boolean online = staffProfile != null;
                long sessionDuration = staffProfile != null ? staffProfile.getSessionDuration() : 0;

                staffMembers.add(new StaffMemberEntry(
                        entry.getId(),
                        entry.getUsername(),
                        entry.getRole(),
                        uuid,
                        entry.getMinecraftUsername(),
                        entry.getLastServer(),
                        entry.getLastSeen(),
                        entry.getTotalPlaytimeMs(),
                        entry.getPunishmentsIssuedCount(),
                        online,
                        sessionDuration
                ));

                SkinTextureCache.ensureCached(uuid);
            }
        });
    }

    private List<String> buildLore(StaffMemberEntry entry) {
        LocaleManager localeManager = PluginServices.locale();
        Map<String, String> placeholders = buildLorePlaceholders(entry);

        return localeManager.getMessageList("menus.staff_members.lore", placeholders);
    }

    private @Nullable String knownServer(StaffMemberEntry entry) {
        UUID minecraftUuid = entry.getMinecraftUuid();
        if (entry.isOnline() && minecraftUuid != null) return platform.getPlayerServer(minecraftUuid);
        return entry.getLastServer();
    }

    private Map<String, String> buildLorePlaceholders(StaffMemberEntry entry) {
        LocaleManager locale = localeManager();
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("panel_name", entry.getPanelName() != null ? entry.getPanelName() : locale.getUnknownWord());
        placeholders.put("role", entry.getRole() != null ? entry.getRole() : locale.getUnknownWord());
        placeholders.put("minecraft_username", entry.getMinecraftUsername() != null
                ? entry.getMinecraftUsername() : locale.getMessage("menus.staff_members.not_linked"));
        placeholders.put("is_online", (entry.isOnline() ? "&a" : "&c") + locale.getYesNoWord(entry.isOnline()));
        String knownServer = knownServer(entry);
        placeholders.put("presence", Presence.describe(locale, entry.isOnline(), entry.getSessionDuration(),
                entry.getLastSeen(), knownServer));
        placeholders.put("server", knownServer != null ? knownServer : locale.getUnknownWord());
        placeholders.put("playtime", MenuItems.formatDuration(
                entry.getTotalPlaytimeMs() != null ? entry.getTotalPlaytimeMs() : 0L));
        placeholders.put("punishments_count", String.valueOf(entry.getPunishmentsIssuedCount()));

        return placeholders;
    }

    @Override
    protected Map<Integer, CirrusItem> intercept(int menuSize) {
        Map<Integer, CirrusItem> items = super.intercept(menuSize);

        items.put(MenuSlots.FILTER_BUTTON, MenuItems.filterButton(FILTERS, currentFilter));

        return items;
    }

    @Override
    protected Collection<StaffMemberEntry> items() {
        List<StaffMemberEntry> filtered;
        if (FILTER_ONLINE.equals(currentFilter)) {
            filtered = staffMembers.stream().filter(StaffMemberEntry::isOnline).collect(Collectors.toList());
        } else if (FILTER_OFFLINE.equals(currentFilter)) {
            filtered = staffMembers.stream().filter(e -> !e.isOnline()).collect(Collectors.toList());
        } else {
            filtered = new ArrayList<>(staffMembers);
        }

        return filtered;
    }

    @Override
    protected CirrusItem emptyPlaceholder() {
        String emptyKey = FILTER_ONLINE.equals(currentFilter) ? "menus.empty.staff_online" :
                FILTER_OFFLINE.equals(currentFilter) ? "menus.empty.staff_offline" : "menus.empty.staff_members";
        return createEmptyPlaceholder(localeManager().getMessage(emptyKey));
    }

    @Override
    protected CirrusItem mapItem(StaffMemberEntry entry) {
        List<String> lore = buildLore(entry);

        LocaleManager localeManager = PluginServices.locale();
        String title = localeManager.getMessage("menus.staff_members.title", mapOf(
                "panel_name", entry.getPanelName() != null ? entry.getPanelName() : localeManager.getUnknownWord()
        ));

        CirrusItem headItem = CirrusItem.of(
                CirrusItemType.PLAYER_HEAD,
                CirrusChatElement.ofLegacyText(title),
                MenuItems.lore(lore)
        );

        headItem = SkinTextureCache.applyCached(headItem, entry.getMinecraftUuid());

        return headItem;
    }

    @Override
    protected void handleItemClick(Click click, StaffMemberEntry entry) {
    }

    @Override
    protected void registerActionHandlers() {
        super.registerActionHandlers();

        registerActionHandler("filter", this::handleFilter);
    }

    private void handleFilter(Click click) {
        String nextFilter = FILTERS.next(currentFilter);

        ActionHandlers.openMenu(
                new StaffMembersMenu(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, backAction,
                        nextFilter, staffMembers))
                .handle(click);
    }
}
