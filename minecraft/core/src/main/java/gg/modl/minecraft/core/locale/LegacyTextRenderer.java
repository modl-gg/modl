package gg.modl.minecraft.core.locale;

import dev.simplix.cirrus.text.LegacyColorCodes;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LegacyTextRenderer {
    private static final char SECTION = '\u00a7';

    private static final Pattern MINIMESSAGE_TAG_PATTERN = Pattern.compile("<[a-zA-Z_/!#][^>]*>");
    private static final Pattern MINIMESSAGE_TAG_REPLACE_PATTERN = Pattern.compile("<(/?)([^>\\s]+)(?:\\s[^>]*)?>");
    private static final Map<String, String> TAGS = createTags();
    private static final Set<String> COLOR_TAGS = new HashSet<>(Arrays.asList("color", "colour", "c"));
    private static final Set<String> LINE_BREAKS = new HashSet<>(Arrays.asList("br", "newline"));
    private static final Set<String> UNRENDERED_TAGS = new HashSet<>(Arrays.asList("rainbow", "gradient", "transition",
            "pride", "shadow", "click", "hover", "insertion", "font", "key", "lang", "tr", "translate",
            "selector", "sel", "score", "nbt", "data", "color", "colour", "c", "sprite", "head"));

    private LegacyTextRenderer() {}

    public static boolean isMiniMessage(String text) {
        return text != null && !text.isEmpty() && MINIMESSAGE_TAG_PATTERN.matcher(text).find();
    }

    public static String colorize(String text) {
        if (text == null || text.isEmpty()) return text;
        return LegacyColorCodes.translateAmpersandCodes(translateMiniMessageTags(text));
    }

    private static String translateMiniMessageTags(String text) {
        Matcher matcher = MINIMESSAGE_TAG_REPLACE_PATTERN.matcher(text);
        StringBuilder translated = new StringBuilder(text.length());
        int copiedUpTo = 0;
        while (matcher.find()) {
            translated.append(text, copiedUpTo, matcher.start())
                    .append(resolveTag(matcher.group(), matcher.group(1), matcher.group(2)));
            copiedUpTo = matcher.end();
        }
        return translated.append(text, copiedUpTo, text.length()).toString();
    }

    private static String resolveTag(String written, String closingMarker, String rawTag) {
        String tag = rawTag.toLowerCase(Locale.ROOT);
        if (!isMarkup(tag)) return written;
        if (closingMarker != null && !closingMarker.isEmpty()) return "";
        if (LINE_BREAKS.contains(tag)) return "\n";
        if (isHexColor(tag)) return hexToLegacy(tag);
        String legacyCode = TAGS.get(tag);
        if (legacyCode != null) return legacyCode;
        int argumentIndex = tag.indexOf(':');
        if (argumentIndex < 0 || !COLOR_TAGS.contains(tag.substring(0, argumentIndex))) return "";
        String argument = tag.substring(argumentIndex + 1);
        if (isHexColor(argument)) return hexToLegacy(argument);
        String namedColor = TAGS.get(argument);
        return namedColor != null ? namedColor : "";
    }

    private static boolean isMarkup(String tag) {
        String name = tag.indexOf(':') >= 0 ? tag.substring(0, tag.indexOf(':')) : tag;
        return TAGS.containsKey(name) || UNRENDERED_TAGS.contains(name) || LINE_BREAKS.contains(name) || isHexColor(name);
    }

    private static boolean isHexColor(String tag) {
        return tag.length() == 7 && tag.charAt(0) == '#' && tag.substring(1).matches("[0-9a-f]{6}");
    }

    private static String hexToLegacy(String tag) {
        StringBuilder legacy = new StringBuilder(14);
        legacy.append(SECTION).append('x');
        for (int i = 1; i < tag.length(); i++) {
            legacy.append(SECTION).append(tag.charAt(i));
        }
        return legacy.toString();
    }

    private static Map<String, String> createTags() {
        Map<String, String> tags = new HashMap<>();
        tags.put("black", code('0'));
        tags.put("dark_blue", code('1'));
        tags.put("dark_green", code('2'));
        tags.put("dark_aqua", code('3'));
        tags.put("dark_red", code('4'));
        tags.put("dark_purple", code('5'));
        tags.put("gold", code('6'));
        tags.put("gray", code('7'));
        tags.put("grey", code('7'));
        tags.put("dark_gray", code('8'));
        tags.put("dark_grey", code('8'));
        tags.put("blue", code('9'));
        tags.put("green", code('a'));
        tags.put("aqua", code('b'));
        tags.put("red", code('c'));
        tags.put("light_purple", code('d'));
        tags.put("yellow", code('e'));
        tags.put("white", code('f'));
        tags.put("obfuscated", code('k'));
        tags.put("bold", code('l'));
        tags.put("strikethrough", code('m'));
        tags.put("underlined", code('n'));
        tags.put("underline", code('n'));
        tags.put("italic", code('o'));
        tags.put("reset", code('r'));
        tags.put("b", code('l'));
        tags.put("i", code('o'));
        tags.put("em", code('o'));
        tags.put("u", code('n'));
        tags.put("st", code('m'));
        tags.put("obf", code('k'));
        return Collections.unmodifiableMap(tags);
    }

    private static String code(char code) {
        return String.valueOf(SECTION) + code;
    }
}
