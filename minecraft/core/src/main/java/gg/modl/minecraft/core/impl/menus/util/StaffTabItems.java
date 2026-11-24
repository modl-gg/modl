package gg.modl.minecraft.core.impl.menus.util;

import dev.simplix.cirrus.item.CirrusItem;
import dev.simplix.cirrus.item.CirrusItemType;

import gg.modl.minecraft.core.staff.action.StaffRequirement;
import gg.modl.minecraft.core.staff.action.StaffRequirements;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public final class StaffTabItems {
    private static final String TAB_KEY_PREFIX = "menus.staff_tabs.";

    private StaffTabItems() {}

    public static Map<Integer, CirrusItem> create(boolean compact, Predicate<StaffRequirement> allows) {
        Map<Integer, CirrusItem> items = new HashMap<>();

        put(items, compact ? MenuSlots.COMPACT_STAFF_ONLINE : MenuSlots.STAFF_ONLINE_PLAYERS,
                CirrusItemType.PLAYER_HEAD, "online_players", "openOnlinePlayers", compact);
        put(items, compact ? MenuSlots.COMPACT_STAFF_REPORTS : MenuSlots.STAFF_REPORTS,
                CirrusItemType.ENDER_EYE, "reports", "openReports", compact);
        if (allows.test(StaffRequirements.VIEW_PLAYERS)) {
            put(items, compact ? MenuSlots.COMPACT_STAFF_PUNISHMENTS : MenuSlots.STAFF_PUNISHMENTS,
                    CirrusItemType.IRON_SWORD, "punishments", "openPunishments", compact);
        }
        put(items, compact ? MenuSlots.COMPACT_STAFF_TICKETS : MenuSlots.STAFF_TICKETS,
                CirrusItemType.PAPER, "tickets", "openTickets", compact);
        put(items, compact ? MenuSlots.COMPACT_STAFF_PANEL : MenuSlots.STAFF_PANEL_LINK,
                CirrusItemType.COMPASS, "panel", "openPanel", compact);
        put(items, compact ? MenuSlots.COMPACT_STAFF_SETTINGS : MenuSlots.STAFF_SETTINGS,
                CirrusItemType.COMMAND_BLOCK, "settings", "openSettings", compact);

        return items;
    }

    private static void put(Map<Integer, CirrusItem> items, int slot, CirrusItemType type, String tab,
                            String actionHandler, boolean compact) {
        items.put(slot, TabItem.create(type, TAB_KEY_PREFIX + tab, compact, mapOf()).actionHandler(actionHandler));
    }
}
