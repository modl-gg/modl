package gg.modl.minecraft.core.impl.menus.util;

import dev.simplix.cirrus.item.CirrusItem;
import dev.simplix.cirrus.item.CirrusItemType;
import dev.simplix.cirrus.text.CirrusChatElement;
import gg.modl.minecraft.api.Note;
import gg.modl.minecraft.api.Punishment;
import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.locale.LocaleManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static gg.modl.minecraft.core.util.Java8Collections.listOf;
import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public final class PunishmentItemRenderer {
    private static final String STATUS_KEY_PREFIX = "menus.history_item";
    private static final Pattern WORDS = Pattern.compile("[^\\p{L}\\p{N}]+");

    private PunishmentItemRenderer() {}

    public static CirrusItem renderSubject(Punishment punishment) {
        return render(punishment, punishment.getTypeCategory(), "menus.history_item", Collections.emptyMap(), false);
    }

    public static CirrusItem render(Punishment punishment, String typeName, String loreKeyPrefix, Map<String, String> extraVars) {
        return render(punishment, typeName, loreKeyPrefix, extraVars, true);
    }

    private static CirrusItem render(Punishment punishment, String typeName, String loreKeyPrefix,
                                     Map<String, String> extraVars, boolean clickable) {
        LocaleManager locale = PluginServices.locale();
        boolean isKick = punishment.isKickType();
        boolean isActive = !isKick && isEffectivelyActive(punishment);

        String statusLine = buildStatusLine(locale, punishment, isKick, isActive);

        StringBuilder notesBuilder = new StringBuilder();
        List<Note> notes = punishment.getStaffNotes();
        if (!notes.isEmpty()) {
            String noteFormat = locale.getMessage(STATUS_KEY_PREFIX + ".note_format");
            for (int i = 0; i < notes.size(); i++) {
                Note note = notes.get(i);
                String noteDate = MenuItems.formatDate(note.getDate());
                String noteIssuer = note.getIssuerName();
                String noteText = note.getText();
                String formattedNote = noteFormat
                        .replace("{note_date}", noteDate)
                        .replace("{note_issuer}", noteIssuer)
                        .replace("{note}", noteText);
                if (i > 0) notesBuilder.append("\n");
                notesBuilder.append(formattedNote);
            }
        }

        Map<String, String> vars = new HashMap<>();
        vars.put("punishment_id", punishment.getId());
        vars.put("punishment_type", typeName);
        vars.put("details", titleDetails(locale, punishment, typeName, isKick));
        vars.put("status_line", statusLine);
        vars.put("notes", notesBuilder.toString());
        vars.put("reason", locale.reasonOrDefault(punishment.getIssuedReason()));
        vars.put("issuer", punishment.getIssuerName());
        vars.put("issued_date", MenuItems.formatDate(punishment.getIssued()));
        Object issuedServerObj = punishment.getDataMap().get("issuedServer");
        String issuedServer = issuedServerObj instanceof String ? ((String) issuedServerObj).trim() : "";
        vars.put("issued_server", issuedServer);
        if (extraVars != null) vars.putAll(extraVars);

        List<String> lore = renderLore(locale.getMessageList(loreKeyPrefix + ".lore"), vars);

        if (clickable) lore = MenuItems.loreSections(lore, listOf(locale.getMessage(loreKeyPrefix + ".click_hint")));

        String titleKey = isActive ? loreKeyPrefix + ".title_active" : loreKeyPrefix + ".title_inactive";
        String title = locale.getMessage(titleKey, vars);
        CirrusItemType itemType = itemType(punishment);

        return CirrusItem.of(
                itemType,
                CirrusChatElement.ofLegacyText(title),
                MenuItems.lore(lore)
        );
    }

    static List<String> renderLore(List<String> template, Map<String, String> vars) {
        List<String> lines = new ArrayList<>();
        for (String line : template) {
            if (everyPlaceholderEmpty(line, vars)) continue;
            lines.addAll(Arrays.asList(substitute(line, vars).split("\n")));
        }
        return MenuItems.compactSpacers(lines);
    }

    private static boolean everyPlaceholderEmpty(String line, Map<String, String> vars) {
        boolean anyPlaceholder = false;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            if (!line.contains("{" + entry.getKey() + "}")) continue;
            if (!MenuItems.isSpacer(entry.getValue())) return false;
            anyPlaceholder = true;
        }
        return anyPlaceholder;
    }

    private static String substitute(String line, Map<String, String> vars) {
        String rendered = line;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            rendered = rendered.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return rendered;
    }

    private static String titleDetails(LocaleManager locale, Punishment punishment, String typeName, boolean isKick) {
        List<String> parts = new ArrayList<>();
        if (!isKick) parts.add(initialDuration(locale, punishment));
        String kind = kindWord(locale, punishment, isKick);
        if (!kind.isEmpty() && !namesKind(typeName, kind)) parts.add(kind);
        if (parts.isEmpty()) return "";
        return locale.getMessage("menus.punishment_kind.details", mapOf("details", String.join(" ", parts)));
    }

    static boolean namesKind(String typeName, String kind) {
        for (String word : WORDS.split(typeName)) {
            if (word.equalsIgnoreCase(kind)) return true;
        }
        return false;
    }

    private static String initialDuration(LocaleManager locale, Punishment punishment) {
        Long duration = punishment.getDuration();
        if (duration == null || duration <= 0) return locale.getMessage("general.permanent");
        return MenuItems.formatDuration(duration);
    }

    private static String kindWord(LocaleManager locale, Punishment punishment, boolean isKick) {
        if (isKick) return locale.getMessage("menus.punishment_kind.kick").trim();
        if (punishment.isBanType()) return locale.getMessage("menus.punishment_kind.ban").trim();
        if (punishment.isMuteType()) return locale.getMessage("menus.punishment_kind.mute").trim();
        return "";
    }

    private static String buildStatusLine(LocaleManager locale, Punishment punishment, boolean isKick,
                                          boolean isActive) {
        Instant pardonDate = isKick ? null : punishment.getPardonDate();
        if (isKick) {
            return "";
        }
        if (pardonDate != null) {
            return locale.getMessage(STATUS_KEY_PREFIX + ".status_pardoned",
                    mapOf("pardoned", MenuItems.formatTimeSince(pardonDate)));
        }
        Instant rollbackDate = punishment.getRollbackDate();
        if (rollbackDate != null) {
            return locale.getMessage(STATUS_KEY_PREFIX + ".status_rolled_back",
                    mapOf("rolled_back", MenuItems.formatTimeSince(rollbackDate)));
        }
        if (punishment.getStarted() == null) {
            return locale.getMessage(STATUS_KEY_PREFIX + ".status_unstarted");
        }
        Instant expiry = punishment.getEffectiveExpiry();
        if (isActive) {
            if (expiry == null) return locale.getMessage(STATUS_KEY_PREFIX + ".status_permanent");
            long remaining = Math.max(0, expiry.toEpochMilli() - System.currentTimeMillis());
            return locale.getMessage(STATUS_KEY_PREFIX + ".status_active", mapOf("expiry", MenuItems.formatDuration(remaining)));
        }
        String expired = expiry != null ? MenuItems.formatTimeSince(expiry) : "N/A";
        return locale.getMessage(STATUS_KEY_PREFIX + ".status_inactive", mapOf("expired", expired));
    }

    public static boolean isEffectivelyActive(Punishment punishment) {
        return punishment.getStarted() != null && punishment.isActive();
    }

    public static CirrusItemType itemType(Punishment punishment) {
        int ordinal = punishment.getTypeOrdinal();

        Cache cache = PluginServices.cache();
        if (cache != null) {
            Map<Integer, String> items = cache.getPunishmentTypeItems();
            if (items != null) {
                String itemId = items.get(ordinal);
                if (itemId != null) return CirrusItemType.of(itemId);
            }
        }

        if (punishment.isBanType()) return CirrusItemType.BARRIER;
        if (punishment.isMuteType()) return CirrusItemType.PAPER;
        if (punishment.isKickType()) return CirrusItemType.LEATHER_BOOTS;
        return CirrusItemType.PAPER;
    }
}
