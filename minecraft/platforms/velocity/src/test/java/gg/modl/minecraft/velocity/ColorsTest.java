package gg.modl.minecraft.velocity;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ColorsTest {

    @Test
    void ampersandAndSectionCodesBothBecomeColours() {
        assertEquals(Component.text("Banned", NamedTextColor.RED), Colors.get("&cBanned"));
        assertEquals(Component.text("Banned", NamedTextColor.RED), Colors.get("§cBanned"));
    }

    @Test
    void ampersandPairsThatAreNotColourCodesStayLiteral() {
        for (String text : new String[]{"Tom & Jerry", "&z marks the spot", "&x&#ff0000", "Salt &pepper"}) {
            assertEquals(Component.text(text), Colors.get(text), text);
        }
    }

    @Test
    void theHexColourThatMiniMessageTagsRenderToIsKept() {
        assertEquals(Component.text("Notice", TextColor.color(0xFF8800)),
                Colors.get("§x§f§f§8§8§0§0Notice"));
    }
}
