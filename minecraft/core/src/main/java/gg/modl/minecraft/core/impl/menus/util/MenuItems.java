package gg.modl.minecraft.core.impl.menus.util;

import dev.simplix.cirrus.item.CirrusItem;
import dev.simplix.cirrus.item.CirrusItemType;
import dev.simplix.cirrus.text.CirrusChatElement;
import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.core.locale.LocaleManager;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public final class MenuItems {
    public static final String CLICK_TO_TOGGLE = "menus.common.click_to_toggle";
    private static final Pattern LEGACY_FORMAT = Pattern.compile("(?i)[&\u00a7][0-9a-fk-or]");
    private MenuItems() {}

    public static final String COLOR_GOLD = "§6", COLOR_YELLOW = "§e", COLOR_GREEN = "§a", COLOR_RED = "§c",
            COLOR_GRAY = "§7", COLOR_DARK_GRAY = "§8", COLOR_WHITE = "§f", COLOR_AQUA = "§b";

    public static CirrusItem backButton() {
        return localized(CirrusItemType.RED_BED, "menus.common.back", "menus.common.back_lore").actionHandler("back");
    }

    public static CirrusItem previousPageButton() {
        return named(CirrusItemType.ARROW, "menus.common.previous_page").actionHandler("previousPage");
    }

    public static CirrusItem nextPageButton() {
        return named(CirrusItemType.ARROW, "menus.common.next_page").actionHandler("nextPage");
    }

    public static CirrusItem named(CirrusItemType type, String nameKey) {
        return CirrusItem.of(type, CirrusChatElement.ofLegacyText(locale().getMessage(nameKey)));
    }

    public static CirrusItem localized(CirrusItemType type, String nameKey, String loreKey) {
        return localized(type, nameKey, loreKey, mapOf());
    }

    public static CirrusItem localized(CirrusItemType type, String nameKey, String loreKey, Map<String, String> placeholders) {
        LocaleManager locale = locale();
        return CirrusItem.of(
                type,
                CirrusChatElement.ofLegacyText(locale.getMessage(nameKey, placeholders)),
                lore(locale.getMessageList(loreKey, placeholders))
        );
    }

    public static CirrusItem playerHead(String title, List<String> loreLines) {
        return CirrusItem.of(
                CirrusItemType.PLAYER_HEAD,
                CirrusChatElement.ofLegacyText(title),
                lore(loreLines)
        );
    }

    public static CirrusItem filterButton(OptionCycle filters, String currentFilter) {
        LocaleManager locale = locale();
        return optionButton(locale, "menus.common.filter",
                optionLore(locale, "menus.common.filter_by_type", filters, currentFilter, "menus.common.cycle_filters"),
                "filter");
    }

    public static CirrusItem filterButton(OptionCycle filters, String currentFilter, String currentStatusFilter, String itemsKey) {
        LocaleManager locale = locale();
        List<String> loreLines = optionLore(locale, "menus.common.filter_by_type", filters, currentFilter, "menus.common.cycle_filters");
        loreLines.add(locale.getMessage("menus.common.status_right_click_toggle",
                statusPlaceholders(locale, !isOpenStatus(currentStatusFilter), itemsKey)));
        return optionButton(locale, "menus.common.filter", loreLines, "filter");
    }

    public static CirrusItem statusToggleButton(String currentStatusFilter, String itemsKey) {
        LocaleManager locale = locale();
        boolean showingOpen = isOpenStatus(currentStatusFilter);
        List<String> loreLines = new ArrayList<>();
        loreLines.add(locale.getMessage("menus.common.status_showing", statusPlaceholders(locale, showingOpen, itemsKey)));
        loreLines.add("");
        loreLines.add(locale.getMessage("menus.common.status_click_toggle", statusPlaceholders(locale, !showingOpen, itemsKey)));
        return optionButton(locale, "menus.common.filter", loreLines, "filter");
    }

    public static CirrusItem sortButton(OptionCycle sorts, String currentSort) {
        LocaleManager locale = locale();
        return optionButton(locale, "menus.common.sort",
                optionLore(locale, "menus.common.sort_by", sorts, currentSort, "menus.common.cycle_sort"),
                "sort");
    }

    private static CirrusItem optionButton(LocaleManager locale, String titleKey, List<String> loreLines, String actionHandler) {
        return CirrusItem.of(
                CirrusItemType.ANVIL,
                CirrusChatElement.ofLegacyText(locale.getMessage(titleKey)),
                lore(loreLines)
        ).actionHandler(actionHandler);
    }

    private static List<String> optionLore(LocaleManager locale, String headerKey, OptionCycle options,
                                           String currentOption, String hintKey) {
        List<String> loreLines = new ArrayList<>();
        loreLines.add(locale.getMessage(headerKey));
        loreLines.add("");
        for (String option : options.ids()) {
            String lineKey = option.equalsIgnoreCase(currentOption) ? "menus.common.option_selected" : "menus.common.option_unselected";
            loreLines.add(locale.getMessage(lineKey, mapOf("option", options.label(locale, option))));
        }
        loreLines.add("");
        loreLines.add(locale.getMessage(hintKey));
        return loreLines;
    }

    private static boolean isOpenStatus(String statusFilter) {
        return "open".equalsIgnoreCase(statusFilter);
    }

    private static Map<String, String> statusPlaceholders(LocaleManager locale, boolean open, String itemsKey) {
        return mapOf(
                "status", locale.getMessage(open ? "menus.common.status_open" : "menus.common.status_closed"),
                "items", locale.getMessage(itemsKey)
        );
    }

    public static String formatDate(@Nullable Instant date) {
        return locale().formatDate(date);
    }

    public static String formatTimeSince(Instant date) {
        return formatDuration(Math.max(0, System.currentTimeMillis() - date.toEpochMilli()));
    }

    public static String formatDuration(@Nullable Long durationMs) {
        if (durationMs == null || durationMs < 0) return locale().getMessage("general.permanent");

        long seconds = durationMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours % 24 > 0) sb.append(hours % 24).append("h ");
        if (minutes % 60 > 0) sb.append(minutes % 60).append("m ");
        if (seconds % 60 > 0 && days == 0) sb.append(seconds % 60).append("s");

        String result = sb.toString().trim();
        return result.isEmpty() ? "0s" : result;
    }

    public static List<String> wrapText(String text, int wordsPerLine) {
        if (text == null || text.isEmpty())
            return new ArrayList<>();

        String[] words = text.split("\\s+");
        List<String> lines = new ArrayList<>();
        StringBuilder currentLine = new StringBuilder();
        int wordCount = 0;

        for (String word : words) {
            if (wordCount >= wordsPerLine) {
                lines.add(COLOR_WHITE + currentLine.toString().trim());
                currentLine = new StringBuilder();
                wordCount = 0;
            }
            currentLine.append(word).append(" ");
            wordCount++;
        }

        if (currentLine.length() > 0) lines.add(COLOR_WHITE + currentLine.toString().trim());

        return lines;
    }

    public static List<CirrusChatElement> lore(String... lines) {
        return Arrays.stream(lines)
                .map(CirrusChatElement::ofLegacyText)
                .collect(Collectors.toList());
    }

    @SafeVarargs
    public static List<String> loreSections(List<String>... sections) {
        List<String> lines = new ArrayList<>();
        for (List<String> section : sections) {
            if (isBlank(section)) continue;
            if (!lines.isEmpty()) lines.add("");
            lines.addAll(section);
        }
        return lines;
    }

    private static boolean isBlank(List<String> section) {
        for (String line : section) {
            if (!isSpacer(line)) return false;
        }
        return true;
    }

    public static List<String> compactSpacers(List<String> lines) {
        List<String> compacted = new ArrayList<>();
        @Nullable String pendingSpacer = null;
        for (String line : lines) {
            if (isSpacer(line)) {
                if (!compacted.isEmpty()) pendingSpacer = line;
                continue;
            }
            if (pendingSpacer != null) compacted.add(pendingSpacer);
            pendingSpacer = null;
            compacted.add(line);
        }
        return compacted;
    }

    public static boolean isSpacer(String line) {
        return LEGACY_FORMAT.matcher(line).replaceAll("").trim().isEmpty();
    }

    public static List<CirrusChatElement> lore(List<String> lines) {
        return lines.stream()
                .map(CirrusChatElement::ofLegacyText)
                .collect(Collectors.toList());
    }

    private static LocaleManager locale() {
        return PluginServices.locale();
    }
}
