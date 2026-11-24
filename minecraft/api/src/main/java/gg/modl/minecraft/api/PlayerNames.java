package gg.modl.minecraft.api;

import java.util.regex.Pattern;

public final class PlayerNames {
    public static final String PATTERN = "^([A-Za-z0-9_]{1,16}|[.*+][A-Za-z0-9_]{1,15})$";

    private static final Pattern NAME = Pattern.compile(PATTERN);

    private PlayerNames() {}

    public static boolean isValid(String name) {
        return name != null && NAME.matcher(name).matches();
    }
}
