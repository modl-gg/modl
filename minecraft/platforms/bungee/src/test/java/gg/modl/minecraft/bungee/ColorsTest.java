package gg.modl.minecraft.bungee;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColorsTest {

    @Test
    void ampersandAndSectionCodesBothBecomeColours() {
        for (String text : new String[]{"&cBanned", "§cBanned"}) {
            BaseComponent part = onlyPart(Colors.get(text));
            assertEquals("Banned", part.toPlainText(), text);
            assertEquals(ChatColor.RED, part.getColor(), text);
        }
    }

    @Test
    void ampersandPairsThatAreNotColourCodesStayLiteral() {
        assertEquals("Tom & Jerry &z &x&#ff0000", Colors.get("Tom & Jerry &z &x&#ff0000").toPlainText());
    }

    @Test
    void theHexColourThatMiniMessageTagsRenderToIsKept() {
        BaseComponent part = onlyPart(Colors.get("§x§f§f§8§8§0§0Notice"));

        assertEquals("Notice", part.toPlainText());
        assertEquals(ChatColor.of("#ff8800"), part.getColor());
    }

    private static BaseComponent onlyPart(TextComponent component) {
        List<BaseComponent> parts = component.getExtra();
        assertTrue(parts != null && parts.size() == 1, String.valueOf(parts));
        return parts.get(0);
    }
}
