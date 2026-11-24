package gg.modl.minecraft.spigot;

import dev.simplix.cirrus.text.LegacyColorCodes;

public final class Colors {
    private Colors() {
    }

    public static String get(String text) {
        return LegacyColorCodes.translateAmpersandCodes(text);
    }
}
