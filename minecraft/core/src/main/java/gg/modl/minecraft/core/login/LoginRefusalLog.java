package gg.modl.minecraft.core.login;

import gg.modl.minecraft.core.util.PluginLogger;

import java.util.regex.Pattern;

public final class LoginRefusalLog {
    private static final Pattern LEGACY_FORMAT = Pattern.compile("(?i)\u00a7(?:#[0-9a-f]{6}|[0-9a-fk-orx])");
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");

    private LoginRefusalLog() {
    }

    public static void refused(PluginLogger logger, String player, String refusal) {
        logger.debug("Login blocked for " + player + ": " + summary(refusal));
    }

    static String summary(String refusal) {
        String plain = LEGACY_FORMAT.matcher(refusal).replaceAll("").trim();
        return LINE_BREAK.split(plain, 2)[0].trim();
    }
}
