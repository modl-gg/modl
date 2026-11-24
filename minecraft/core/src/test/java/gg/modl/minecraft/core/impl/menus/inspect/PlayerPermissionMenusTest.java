package gg.modl.minecraft.core.impl.menus.inspect;

import dev.simplix.cirrus.item.CirrusItem;
import gg.modl.minecraft.api.Account;
import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import gg.modl.minecraft.core.impl.menus.util.InspectTabItems;
import gg.modl.minecraft.core.impl.menus.util.MenuSlots;
import gg.modl.minecraft.core.impl.menus.util.StaffTabItems;
import gg.modl.minecraft.core.impl.menus.util.StaffViewer;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.support.FakeCirrusPlayerWrapper;
import gg.modl.minecraft.core.support.FakeModlHttpClient;
import gg.modl.minecraft.core.support.FakePlatform;
import gg.modl.minecraft.core.support.TestAccounts;
import gg.modl.minecraft.core.support.TestPluginServices;
import gg.modl.minecraft.core.util.Permissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerPermissionMenusTest {
    private final UUID viewer = UUID.randomUUID();
    private final Account target = TestAccounts.account(UUID.randomUUID(), "Target");
    private final FakePlatform platform = new FakePlatform();
    private final FakeModlHttpClient client = new FakeModlHttpClient();
    private final Cache cache = new Cache(new CachedProfileRegistry());
    private final LocaleManager locale = new LocaleManager();
    private final PluginServices services = TestPluginServices.install(cache, locale);

    @AfterEach
    void uninstall() {
        PluginServices.uninstall(services);
    }

    @Test
    void staffWhoMayNotViewPlayersGetNoRecordTabs() {
        grant(Permissions.TICKET_VIEW_ALL, Permissions.PLAYER_NOTE, Permissions.PLAYER_WARN);

        Map<Integer, CirrusItem> tabs = inspectTabs();

        assertFalse(tabs.containsKey(MenuSlots.INSPECT_NOTES));
        assertFalse(tabs.containsKey(MenuSlots.INSPECT_ALTS));
        assertFalse(tabs.containsKey(MenuSlots.INSPECT_HISTORY));
        assertTrue(tabs.containsKey(MenuSlots.INSPECT_REPORTS));
        assertFalse(staffTabs().containsKey(MenuSlots.STAFF_PUNISHMENTS));
    }

    @Test
    void viewersGetEveryRecordTab() {
        grant(Permissions.PUNISHMENT_VIEW);

        Map<Integer, CirrusItem> tabs = inspectTabs();

        assertTrue(tabs.containsKey(MenuSlots.INSPECT_NOTES));
        assertTrue(tabs.containsKey(MenuSlots.INSPECT_ALTS));
        assertTrue(tabs.containsKey(MenuSlots.INSPECT_HISTORY));
        assertTrue(staffTabs().containsKey(MenuSlots.STAFF_PUNISHMENTS));
    }

    @Test
    void theInspectAndNotesMenusRefuseToOpenWithoutViewing() {
        grant(Permissions.PLAYER_NOTE);
        FakeCirrusPlayerWrapper player = new FakeCirrusPlayerWrapper(viewer);

        assertNull(new InspectMenu(platform, client, viewer, "Staff", target, null).display(player));
        assertNull(new NotesMenu(platform, client, viewer, "Staff", target, null).display(player));

        assertEquals(locale.getMessage("general.no_permission"), platform.lastMessage());
        assertEquals(2, platform.sentMessages().size());
    }

    @Test
    void onlyTheNotePermissionShowsTheCreateNoteButton() {
        grant(Permissions.PLAYER_WARN, Permissions.PUNISHMENT_MODIFY_NOTE);
        assertNull(notesItems().get(MenuSlots.CREATE_NOTE_BUTTON));

        grant(Permissions.PLAYER_NOTE);
        assertNotNull(notesItems().get(MenuSlots.CREATE_NOTE_BUTTON));
    }

    private Map<Integer, CirrusItem> notesItems() {
        return new NotesMenu(platform, client, viewer, "Staff", target, null).intercept(54);
    }

    private Map<Integer, CirrusItem> inspectTabs() {
        return InspectTabItems.create(false, "Target", 0, 0, new StaffViewer(platform, viewer)::allows);
    }

    private Map<Integer, CirrusItem> staffTabs() {
        return StaffTabItems.create(false, new StaffViewer(platform, viewer)::allows);
    }

    private void grant(String... permissions) {
        cache.cacheStaffPermissions(viewer, "Staff", "staff-1", "Helper", Arrays.asList(permissions));
    }
}
