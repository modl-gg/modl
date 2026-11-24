package gg.modl.minecraft.core.impl.menus.util;

import dev.simplix.cirrus.item.CirrusItem;
import dev.simplix.cirrus.item.CirrusItemType;
import dev.simplix.cirrus.text.CirrusChatElement;
import gg.modl.minecraft.api.Evidence;
import gg.modl.minecraft.api.Punishment;
import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.staff.action.StaffRequirement;
import gg.modl.minecraft.core.staff.action.StaffRequirements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import static gg.modl.minecraft.core.util.Java8Collections.listOf;
import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public final class PunishmentModifyItemFactory {
    private static final String KEY = "menus.modify_punishment.";
    private static final String NO_PERMISSION = "menus.common.no_permission";
    private static final int MAX_LISTED_ENTRIES = 5;

    private final Punishment punishment;

    public PunishmentModifyItemFactory(Punishment punishment) {
        this.punishment = punishment;
    }

    public List<CirrusItem> itemsFor(Predicate<StaffRequirement> allows) {
        List<CirrusItem> items = new ArrayList<>();
        items.add(addNote(allows.test(StaffRequirements.ADD_PUNISHMENT_NOTE)));
        items.add(evidence(allows.test(StaffRequirements.MANAGE_PUNISHMENT_EVIDENCE)));
        items.add(pardon(allows.test(StaffRequirements.PARDON_PUNISHMENT)));
        items.add(duration(allows));
        items.add(linkedTickets(allows.test(StaffRequirements.LINK_PUNISHMENT_TICKETS)));
        if (isBanType()) {
            boolean canChangeOptions = allows.test(StaffRequirements.CHANGE_PUNISHMENT_OPTIONS);
            items.add(statWipe(canChangeOptions));
            items.add(altBlock(canChangeOptions));
        }
        return items;
    }

    private boolean isBanType() {
        return punishment.getType() == Punishment.Type.BAN ||
                punishment.getType() == Punishment.Type.SECURITY_BAN ||
                punishment.getType() == Punishment.Type.LINKED_BAN ||
                punishment.getType() == Punishment.Type.BLACKLIST;
    }

    private CirrusItem addNote(boolean canModify) {
        LocaleManager locale = PluginServices.locale();
        if (canModify) {
            return item(locale, CirrusItemType.OAK_SIGN, KEY + "add_note.name",
                    locale.getMessageList(KEY + "add_note.lore"))
                    .slot(MenuSlots.MODIFY_ADD_NOTE).actionHandler("addNote");
        }
        return item(locale, CirrusItemType.OAK_SIGN, KEY + "add_note.name_locked",
                listOf(locale.getMessage(NO_PERMISSION)))
                .slot(MenuSlots.MODIFY_ADD_NOTE);
    }

    private CirrusItem evidence(boolean canModify) {
        LocaleManager locale = PluginServices.locale();
        List<String> evidenceLore = new ArrayList<>();
        List<Evidence> evidenceList = punishment.getEvidence();
        if (evidenceList.isEmpty()) {
            evidenceLore.add(locale.getMessage(KEY + "evidence.none"));
        } else {
            evidenceLore.add(locale.getMessage(KEY + "evidence.header", mapOf("count", String.valueOf(evidenceList.size()))));
            for (int i = 0; i < Math.min(evidenceList.size(), MAX_LISTED_ENTRIES); i++) {
                String display = evidenceList.get(i).getDisplayText();
                if (display.length() > 40) display = display.substring(0, 37) + "...";
                evidenceLore.add(locale.getMessageWithRawValues(KEY + "evidence.entry", mapOf("evidence", display)));
            }
            if (evidenceList.size() > MAX_LISTED_ENTRIES)
                evidenceLore.add(locale.getMessage(KEY + "evidence.more",
                        mapOf("count", String.valueOf(evidenceList.size() - MAX_LISTED_ENTRIES))));
        }

        CirrusItemType itemType = evidenceList.isEmpty() ? CirrusItemType.ARROW : CirrusItemType.SPECTRAL_ARROW;
        evidenceLore.add("");

        if (canModify) {
            evidenceLore.add(locale.getMessage(KEY + "evidence.left_click_add"));
            if (!evidenceList.isEmpty())
                evidenceLore.add(locale.getMessage(KEY + "evidence.right_click_view"));

            return item(locale, itemType, KEY + "evidence.name", evidenceLore)
                    .slot(MenuSlots.MODIFY_EVIDENCE).actionHandler("evidence");
        }

        evidenceLore.add(locale.getMessage("menus.common.no_permission_modify"));
        return item(locale, itemType, KEY + "evidence.name_locked", evidenceLore).slot(MenuSlots.MODIFY_EVIDENCE);
    }

    private CirrusItem pardon(boolean canModify) {
        LocaleManager locale = PluginServices.locale();
        if (canModify) {
            return item(locale, CirrusItemType.GOLDEN_APPLE, KEY + "pardon.name",
                    locale.getMessageList(KEY + "pardon.lore"))
                    .slot(MenuSlots.MODIFY_PARDON).actionHandler("pardon");
        }
        return item(locale, CirrusItemType.GOLDEN_APPLE, KEY + "pardon.name_locked",
                listOf(locale.getMessage(NO_PERMISSION)))
                .slot(MenuSlots.MODIFY_PARDON);
    }

    private CirrusItem duration(Predicate<StaffRequirement> allows) {
        LocaleManager locale = PluginServices.locale();
        String current = locale.getMessage(KEY + "duration.current",
                mapOf("duration", MenuItems.formatDuration(punishment.getEffectiveDuration())));
        if (!allows.test(StaffRequirements.CHANGE_PUNISHMENT_DURATION)) return lockedDuration(locale, current, NO_PERMISSION);
        boolean mayShorten = StaffRequirements.allowsShortening(allows);
        if (!mayShorten && punishment.endsEarlierWith(Long.MAX_VALUE, System.currentTimeMillis())) {
            return lockedDuration(locale, current, KEY + "duration.needs_pardon");
        }
        List<String> lore = MenuItems.loreSections(listOf(current),
                mayShorten ? Collections.<String>emptyList() : listOf(locale.getMessage(KEY + "duration.lengthen_only")));
        return item(locale, CirrusItemType.ANVIL, KEY + "duration.name", lore)
                .slot(MenuSlots.MODIFY_DURATION).actionHandler("changeDuration");
    }

    private CirrusItem lockedDuration(LocaleManager locale, String current, String reasonKey) {
        return item(locale, CirrusItemType.ANVIL, KEY + "duration.name_locked",
                MenuItems.loreSections(listOf(current), listOf(locale.getMessage(reasonKey))))
                .slot(MenuSlots.MODIFY_DURATION);
    }

    private CirrusItem linkedTickets(boolean canModify) {
        LocaleManager locale = PluginServices.locale();
        List<String> ticketIds = punishment.getAttachedTicketIds();
        List<String> ticketLore = new ArrayList<>();
        if (ticketIds.isEmpty()) {
            ticketLore.add(locale.getMessage(KEY + "linked_tickets.none"));
        } else {
            ticketLore.add(locale.getMessage(KEY + "linked_tickets.header", mapOf("count", String.valueOf(ticketIds.size()))));
            for (int i = 0; i < Math.min(ticketIds.size(), MAX_LISTED_ENTRIES); i++) {
                ticketLore.add(locale.getMessage(KEY + "linked_tickets.entry", mapOf("ticket", String.valueOf(ticketIds.get(i)))));
            }
            if (ticketIds.size() > MAX_LISTED_ENTRIES)
                ticketLore.add(locale.getMessage(KEY + "linked_tickets.more",
                        mapOf("count", String.valueOf(ticketIds.size() - MAX_LISTED_ENTRIES))));
        }
        ticketLore.add("");
        ticketLore.add(locale.getMessage(KEY + "linked_tickets.left_click_view"));
        if (canModify)
            ticketLore.add(locale.getMessage(KEY + "linked_tickets.right_click_link"));

        return item(locale, CirrusItemType.ENDER_EYE, KEY + "linked_tickets.name", ticketLore)
                .slot(MenuSlots.MODIFY_LINKED_TICKETS).actionHandler("linkedTickets");
    }

    private CirrusItem statWipe(boolean canModify) {
        boolean statWipe = punishment.isWipeAfterExpiry();
        CirrusItemType itemType = statWipe ? CirrusItemType.EXPERIENCE_BOTTLE : CirrusItemType.GLASS_BOTTLE;
        CirrusItem item = optionToggle(KEY + "stat_wipe.", statWipe, itemType, canModify).slot(MenuSlots.MODIFY_STAT_WIPE);
        return canModify ? item.actionHandler("toggleStatWipe") : item;
    }

    private CirrusItem altBlock(boolean canModify) {
        boolean altBlock = Boolean.TRUE.equals(punishment.getDataMap().get("altBlocking"));
        CirrusItemType itemType = altBlock ? CirrusItemType.TORCH : CirrusItemType.REDSTONE_TORCH;
        CirrusItem item = optionToggle(KEY + "alt_block.", altBlock, itemType, canModify).slot(MenuSlots.MODIFY_ALT_BLOCK);
        return canModify ? item.actionHandler("toggleAltBlock") : item;
    }

    private CirrusItem optionToggle(String optionKey, boolean enabled, CirrusItemType itemType, boolean canModify) {
        LocaleManager locale = PluginServices.locale();
        String current = locale.getMessage(KEY + "current_state",
                mapOf("state", locale.getMessage(enabled ? "menus.common.enabled" : "menus.common.disabled")));
        if (canModify) {
            String action = locale.getMessage(optionKey + (enabled ? "disable_lore" : "enable_lore"));
            return item(locale, itemType, optionKey + "name", listOf(action, "", current));
        }
        return item(locale, itemType, optionKey + "name_locked", listOf(current, "", locale.getMessage(NO_PERMISSION)));
    }

    private static CirrusItem item(LocaleManager locale, CirrusItemType type, String nameKey, List<String> lore) {
        return CirrusItem.of(type, CirrusChatElement.ofLegacyText(locale.getMessage(nameKey)), MenuItems.lore(lore));
    }
}
