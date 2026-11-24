package gg.modl.minecraft.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import gg.modl.minecraft.core.util.CommandUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SectionCodeVelocityActorTest {
    private final CommandSource source = mock(CommandSource.class);
    private final SectionCodeVelocityActor actor = new SectionCodeVelocityActor(source, null);

    @Test
    void sectionCodedRepliesRenderAsColoursInsteadOfLiteralCodes() {
        actor.sendRawMessage("§aStaff mode enabled");

        assertEquals(Component.text("Staff mode enabled", NamedTextColor.GREEN), sent());
    }

    @Test
    void ampersandCodedRepliesRenderToo() {
        actor.sendRawMessage("&eClick to inspect");

        assertEquals(Component.text("Click to inspect", NamedTextColor.YELLOW), sent());
    }

    @Test
    void angleBracketsInUsageTextStayLiteral() {
        actor.sendRawMessage("&cUsage: /ban <player> [reason]");

        assertEquals(Component.text("Usage: /ban <player> [reason]", NamedTextColor.RED), sent());
    }

    @Test
    void theNetworkConsoleActorRendersSectionCodesToo() {
        CommandSource console = mock(ConsoleCommandSource.class);
        new VelocityNetworkConsoleActor(console, null).sendRawMessage("\u00a7aSent to the proxy");

        ArgumentCaptor<ComponentLike> message = ArgumentCaptor.forClass(ComponentLike.class);
        verify(console).sendMessage(message.capture());
        assertEquals(Component.text("Sent to the proxy", NamedTextColor.GREEN), message.getValue().asComponent());
    }

    @Test
    void theProxyConsoleIsTheConsoleActor() {
        SectionCodeVelocityActor console = new SectionCodeVelocityActor(mock(ConsoleCommandSource.class), null);

        assertEquals(CommandUtil.CONSOLE_UUID, console.uniqueId());
    }

    private Component sent() {
        ArgumentCaptor<ComponentLike> message = ArgumentCaptor.forClass(ComponentLike.class);
        verify(source).sendMessage(message.capture());
        return message.getValue().asComponent();
    }
}
