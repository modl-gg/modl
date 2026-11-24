package gg.modl.minecraft.velocity;

import dev.simplix.cirrus.text.LegacyColorCodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class Colors {
    private static final LegacyComponentSerializer SECTION_CODES = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private Colors() {}

    public static Component get(String text) {
        return SECTION_CODES.deserialize(LegacyColorCodes.translateAmpersandCodes(text));
    }
}
