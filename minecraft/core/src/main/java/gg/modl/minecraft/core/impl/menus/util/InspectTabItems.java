package gg.modl.minecraft.core.impl.menus.util;

import dev.simplix.cirrus.item.CirrusItem;
import dev.simplix.cirrus.item.CirrusItemType;
import gg.modl.minecraft.core.staff.action.StaffRequirement;
import gg.modl.minecraft.core.staff.action.StaffRequirements;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public final class InspectTabItems {
    private static final String TAB_KEY_PREFIX = "menus.inspect_tabs.";

    private InspectTabItems() {}

    public enum InspectTab {
        NOTES("openNotes", StaffRequirements.VIEW_PLAYERS),
        ALTS("openAlts", StaffRequirements.VIEW_PLAYERS),
        HISTORY("openHistory", StaffRequirements.VIEW_PLAYERS),
        REPORTS("openReports", StaffRequirements.STAFF),
        PUNISH("openPunish", StaffRequirements.STAFF);

        @Getter private final String action;
        @Getter private final StaffRequirement requirement;

        InspectTab(String action, StaffRequirement requirement) {
            this.action = action;
            this.requirement = requirement;
        }
    }

    public static Map<Integer, CirrusItem> create(boolean compact, String targetName, int punishmentCount, int noteCount,
                                                  Predicate<StaffRequirement> allows) {
        Map<Integer, CirrusItem> items = new HashMap<>();

        put(items, allows, InspectTab.NOTES, compact ? MenuSlots.COMPACT_INSPECT_NOTES : MenuSlots.INSPECT_NOTES,
                CirrusItemType.PAPER, "notes", compact, placeholders(targetName, noteCount));
        put(items, allows, InspectTab.ALTS, compact ? MenuSlots.COMPACT_INSPECT_ALTS : MenuSlots.INSPECT_ALTS,
                CirrusItemType.VINE, "alts", compact, placeholders(targetName, 0));
        put(items, allows, InspectTab.HISTORY, compact ? MenuSlots.COMPACT_INSPECT_HISTORY : MenuSlots.INSPECT_HISTORY,
                CirrusItemType.WRITABLE_BOOK, "history", compact, placeholders(targetName, punishmentCount));
        put(items, allows, InspectTab.REPORTS, compact ? MenuSlots.COMPACT_INSPECT_REPORTS : MenuSlots.INSPECT_REPORTS,
                CirrusItemType.ENDER_EYE, "reports", compact, placeholders(targetName, 0));
        put(items, allows, InspectTab.PUNISH, compact ? MenuSlots.COMPACT_INSPECT_PUNISH : MenuSlots.INSPECT_PUNISH,
                CirrusItemType.BOW, "punish", compact, placeholders(targetName, 0));

        return items;
    }

    private static Map<String, String> placeholders(String targetName, int count) {
        return mapOf("player", targetName, "count", String.valueOf(count));
    }

    private static void put(Map<Integer, CirrusItem> items, Predicate<StaffRequirement> allows, InspectTab tab, int slot,
                            CirrusItemType type, String key, boolean compact, Map<String, String> placeholders) {
        if (!allows.test(tab.getRequirement())) return;
        items.put(slot, TabItem.create(type, TAB_KEY_PREFIX + key, compact, placeholders).actionHandler(tab.getAction()));
    }
}
