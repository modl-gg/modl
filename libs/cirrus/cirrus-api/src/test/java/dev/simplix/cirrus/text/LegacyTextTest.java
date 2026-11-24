package dev.simplix.cirrus.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyTextTest {

    private static final TextColor HEX_COLOR = TextColor.color(0x123456);

    @Test
    void sectionCodesBecomeNamedColorsAndDecorations() {
        TextComponent greeting = text(LegacyText.deserialize("§cHi §lthere"));
        TextComponent subject = text(greeting.children().get(0));

        assertEquals("Hi ", greeting.content());
        assertEquals(NamedTextColor.RED, greeting.color());
        assertFalse(greeting.hasDecoration(TextDecoration.BOLD));
        assertEquals("there", subject.content());
        assertTrue(subject.hasDecoration(TextDecoration.BOLD));
    }

    @Test
    void bothLegacyHexFormatsBecomeTheirColor() {
        TextComponent bungeeFormat = text(LegacyText.deserialize("§x§1§2§3§4§5§6Hex"));
        TextComponent hashFormat = text(LegacyText.deserialize("§#123456Hex"));

        assertEquals(HEX_COLOR, bungeeFormat.color());
        assertEquals(HEX_COLOR, hashFormat.color());
        assertEquals("Hex", bungeeFormat.content());
    }

    @Test
    void chatElementsKeepTheirAmpersandTextAsSectionCodes() {
        CirrusChatElement element = CirrusChatElement.ofLegacyText("&cHi &lthere");

        assertEquals("§cHi §lthere", element.asLegacyText());
        assertEquals(NamedTextColor.RED, element.asComponent().color());
        assertFalse(element.isEmpty());
        assertTrue(CirrusChatElement.ofLegacyText(null).isEmpty());
        assertEquals("", CirrusChatElement.empty().asLegacyText());
    }

    private static TextComponent text(Component component) {
        assertTrue(component instanceof TextComponent, component.toString());
        return (TextComponent) component;
    }
}
