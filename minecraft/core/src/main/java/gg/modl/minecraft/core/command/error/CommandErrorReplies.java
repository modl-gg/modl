package gg.modl.minecraft.core.command.error;

import gg.modl.minecraft.core.locale.LocaleManager;
import lombok.RequiredArgsConstructor;
import revxrsal.commands.command.CommandActor;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

@RequiredArgsConstructor
final class CommandErrorReplies {
    static final int MAX_VALUE_LENGTH = 256;

    private static final char FULLWIDTH_AMPERSAND = '＆';
    private static final String ELLIPSIS = "...";
    private static final Pattern UNSAFE_CHARACTERS =
            Pattern.compile("[\\u00a7\\p{Cc}\\u2028\\u2029\\u202a-\\u202e\\u2066-\\u2069]");

    private final LocaleManager localeManager;

    void send(CommandActor actor, CommandError error) {
        Map<String, String> shown = new HashMap<>();
        error.getValues().forEach((name, value) -> shown.put(name, asPlainText(value)));
        actor.reply(localeManager.getMessageWithRawValues(error.getLocaleKey(), shown));
    }

    private static String asPlainText(String value) {
        return UNSAFE_CHARACTERS.matcher(abbreviate(value)).replaceAll("").replace('&', FULLWIDTH_AMPERSAND);
    }

    private static String abbreviate(String value) {
        if (value.length() <= MAX_VALUE_LENGTH) return value;
        int end = MAX_VALUE_LENGTH - ELLIPSIS.length();
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + ELLIPSIS;
    }
}
