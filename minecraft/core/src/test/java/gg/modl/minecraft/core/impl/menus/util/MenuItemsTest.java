package gg.modl.minecraft.core.impl.menus.util;

import dev.simplix.cirrus.item.CirrusItem;
import dev.simplix.cirrus.text.CirrusChatElement;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.support.TestPluginServices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MenuItemsTest {
    private LocaleManager locale;

    @BeforeEach
    void installServices() {
        locale = new LocaleManager();
        TestPluginServices.install(new Cache(new CachedProfileRegistry()), locale);
    }

    @Test
    void navigationButtonsReadTheirLabelsFromTheLocale() {
        assertEquals(locale.getMessage("menus.common.back"), name(MenuItems.backButton()));
        assertEquals(locale.getMessageList("menus.common.back_lore"), lore(MenuItems.backButton()));
        assertEquals(locale.getMessage("menus.common.previous_page"), name(MenuItems.previousPageButton()));
        assertEquals(locale.getMessage("menus.common.next_page"), name(MenuItems.nextPageButton()));
    }

    @Test
    void reportFilterButtonListsLocalizedOptionsAndMarksTheCurrentOne() {
        CirrusItem button = MenuItems.filterButton(ReportRenderUtil.REPORT_TYPE_FILTERS, "gameplay", "open",
                "menus.common.items_reports");

        assertEquals(locale.getMessage("menus.common.filter"), name(button));
        assertEquals(Arrays.asList(
                locale.getMessage("menus.common.filter_by_type"),
                "",
                option("menus.common.option_unselected", "menus.common.report_filters.all"),
                option("menus.common.option_selected", "menus.common.report_filters.gameplay"),
                option("menus.common.option_unselected", "menus.common.report_filters.chat"),
                "",
                locale.getMessage("menus.common.cycle_filters"),
                locale.getMessage("menus.common.status_right_click_toggle",
                        status("menus.common.status_closed", "menus.common.items_reports"))
        ), lore(button));
    }

    @Test
    void statusToggleButtonShowsCurrentAndOppositeStatus() {
        CirrusItem button = MenuItems.statusToggleButton("closed", "menus.common.items_tickets");

        assertEquals(Arrays.asList(
                locale.getMessage("menus.common.status_showing",
                        status("menus.common.status_closed", "menus.common.items_tickets")),
                "",
                locale.getMessage("menus.common.status_click_toggle",
                        status("menus.common.status_open", "menus.common.items_tickets"))
        ), lore(button));
    }

    @Test
    void formatDurationUsesLocalizedPermanentWord() {
        assertEquals(locale.getMessage("general.permanent"), MenuItems.formatDuration(null));
    }

    @Test
    void optionCycleWrapsAndFallsBackToTheFirstOption() {
        assertEquals("chat", ReportRenderUtil.REPORT_TYPE_FILTERS.next("gameplay"));
        assertEquals("all", ReportRenderUtil.REPORT_TYPE_FILTERS.next("chat"));
        assertEquals("all", ReportRenderUtil.REPORT_TYPE_FILTERS.next("unknown"));
    }

    private String option(String lineKey, String labelKey) {
        return locale.getMessage(lineKey, Collections.singletonMap("option", locale.getMessage(labelKey)));
    }

    private Map<String, String> status(String statusKey, String itemsKey) {
        return mapOf("status", locale.getMessage(statusKey), "items", locale.getMessage(itemsKey));
    }

    private static String name(CirrusItem item) {
        return item.displayName().asLegacyText();
    }

    private static List<String> lore(CirrusItem item) {
        return item.lore().stream().map(CirrusChatElement::asLegacyText).collect(Collectors.toList());
    }
}
