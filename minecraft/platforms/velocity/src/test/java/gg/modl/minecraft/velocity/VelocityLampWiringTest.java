package gg.modl.minecraft.velocity;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import gg.modl.minecraft.core.util.PluginLogger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import revxrsal.commands.Lamp;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.exception.CommandErrorException;
import revxrsal.commands.velocity.actor.VelocityCommandActor;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VelocityLampWiringTest {
    private static final String KNOWN_PLAYER = "VelTarget";

    private final ProxyServer server = mock(ProxyServer.class);
    private final CommandManager commandManager = mock(CommandManager.class);
    private final CommandSource source = mock(CommandSource.class);
    private final CommandDispatcher<CommandSource> dispatcher = new CommandDispatcher<>();

    @TempDir
    File dataFolder;

    @BeforeEach
    void registerProbeCommandsThroughThePlatform() {
        when(server.getCommandManager()).thenReturn(commandManager);
        when(commandManager.metaBuilder(anyString())).thenReturn(mock(CommandMeta.Builder.class, RETURNS_SELF));
        VelocityPlatform platform = new VelocityPlatform(new Object(), server, mock(Logger.class), dataFolder, "proxy",
                mock(PluginLogger.class), false);

        Lamp<VelocityCommandActor> lamp = platform.buildLamp(builder -> {
        });
        lamp.register(new ProbeCommands());
        platform.finalizeLampRegistration(lamp);

        ArgumentCaptor<BrigadierCommand> registered = ArgumentCaptor.forClass(BrigadierCommand.class);
        verify(commandManager).register(any(), registered.capture());
        dispatcher.getRoot().addChild(registered.getValue().getNode());
    }

    @Test
    void aSectionCodedReplyFromAnExecutedCommandReachesTheSourceAsColours() throws CommandSyntaxException {
        dispatcher.execute("probe reply", source);

        assertEquals(Component.text("Issued ban for VelTarget.", NamedTextColor.GREEN), sent());
    }

    @Test
    void anArgumentReachesTheCommandThroughBrigadier() throws CommandSyntaxException {
        dispatcher.execute("probe find VelTarget", source);

        assertEquals(Component.text("Found VelTarget", NamedTextColor.GREEN), sent());
    }

    @Test
    void aCommandErrorReachesTheSourceInTheErrorColour() throws CommandSyntaxException {
        dispatcher.execute("probe find Nobody", source);

        assertEquals(Component.text("Player not found.", NamedTextColor.RED), sent());
    }

    private Component sent() {
        ArgumentCaptor<ComponentLike> message = ArgumentCaptor.forClass(ComponentLike.class);
        verify(source).sendMessage(message.capture());
        return message.getValue().asComponent();
    }

    public static final class ProbeCommands {
        @Command("probe reply")
        public void reply(VelocityCommandActor actor) {
            actor.reply("§aIssued ban for VelTarget.");
        }

        @Command("probe find")
        public void find(VelocityCommandActor actor, String name) {
            if (!KNOWN_PLAYER.equals(name)) throw new CommandErrorException("Player not found.");
            actor.reply("§aFound " + name);
        }
    }
}
