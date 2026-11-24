package gg.modl.minecraft.spigot;

import gg.modl.minecraft.core.util.ClickableJsonMessage;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.chat.ComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyChatJsonTest {
    @Test
    void aLinkWithHoverTextParsesWithTheBungeeChatOfSpigot188() {
        String json = ClickableJsonMessage.empty()
                .extra(ClickableJsonMessage.text("Open the panel").color("green").underlined(true)
                        .openUrl("https://panel.example.com/login?token=abc")
                        .hoverText("Signs you in \"once\"\nthen expires"))
                .toJson();

        BaseComponent link = ComponentSerializer.parse(json)[0].getExtra().get(0);

        assertEquals(ClickEvent.Action.OPEN_URL, link.getClickEvent().getAction());
        assertEquals("https://panel.example.com/login?token=abc", link.getClickEvent().getValue());
        assertEquals(HoverEvent.Action.SHOW_TEXT, link.getHoverEvent().getAction());
        assertEquals("Signs you in \"once\"\nthen expires", BaseComponent.toPlainText(link.getHoverEvent().getValue()));
    }

    @Test
    void aSuggestedCommandParsesWithTheBungeeChatOfSpigot188() {
        String json = ClickableJsonMessage.empty()
                .suggestCommand("note with \\ backslash")
                .extra(ClickableJsonMessage.text("Note: ").color("gray"))
                .toJson();

        BaseComponent message = ComponentSerializer.parse(json)[0];

        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, message.getClickEvent().getAction());
        assertEquals("note with \\ backslash", message.getClickEvent().getValue());
    }
}
