package gg.modl.minecraft.core.impl.menus.util;

import dev.simplix.cirrus.item.CirrusItem;
import dev.simplix.cirrus.item.CirrusItemType;

import java.util.Map;

final class TabItem {
    private TabItem() {}

    static CirrusItem create(CirrusItemType type, String tabKey, boolean compact, Map<String, String> placeholders) {
        return MenuItems.localized(type, tabKey + ".name", tabKey + (compact ? ".compact_lore" : ".lore"), placeholders);
    }
}
