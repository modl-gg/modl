package gg.modl.minecraft.bungee;

import dev.simplix.cirrus.text.LegacyColorCodes;
import net.md_5.bungee.api.chat.TextComponent;

final class Colors {
    private Colors() {
    }

    @SuppressWarnings("deprecation")
    static TextComponent get(String text) {
        return new TextComponent(TextComponent.fromLegacyText(LegacyColorCodes.translateAmpersandCodes(text)));
    }
}
