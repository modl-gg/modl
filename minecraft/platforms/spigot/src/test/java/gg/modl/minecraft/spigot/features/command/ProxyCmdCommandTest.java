package gg.modl.minecraft.spigot.features.command;

import gg.modl.minecraft.core.network.MemberActionOutcome;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.features.FeatureScheduler;
import gg.modl.minecraft.features.link.CoreLink;
import gg.modl.minecraft.features.locale.FeaturesLocaleManager;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProxyCmdCommandTest {
    private static final String[] ARGS = {"blacklist", "Owner", "x"};

    private final FeaturesLocaleManager locale = mock(FeaturesLocaleManager.class);
    private final CoreLink coreLink = mock(CoreLink.class);
    private final FeatureScheduler scheduler = mock(FeatureScheduler.class);
    private final ProxyCmdCommand command = new ProxyCmdCommand(locale, mock(PluginLogger.class), scheduler, coreLink);

    ProxyCmdCommandTest() {
        when(locale.getMessage("command.proxycmd.console_only")).thenReturn("console only");
        when(coreLink.isConnected()).thenReturn(true);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(scheduler).runOnMainThread(any());
    }

    @Test
    void commandBlocksAndPlayersCannotForwardCommands() {
        CommandSender commandBlock = mock(BlockCommandSender.class);
        CommandSender player = mock(Player.class);

        command.onCommand(commandBlock, mock(Command.class), "proxycmd", ARGS);
        command.onCommand(player, mock(Command.class), "proxycmd", ARGS);

        verify(commandBlock).sendMessage("console only");
        verify(player).sendMessage("console only");
        verify(coreLink, never()).send(any(), any());
    }

    @Test
    void theServerConsoleAndRconCanForwardCommands() {
        command.onCommand(mock(ConsoleCommandSender.class), mock(Command.class), "proxycmd", ARGS);
        command.onCommand(mock(RemoteConsoleCommandSender.class), mock(Command.class), "proxycmd", ARGS);

        verify(coreLink, times(2)).send(any(), any());
    }

    @Test
    void aRateLimitedCommandSaysSoInsteadOfBlamingTheConnection() {
        when(locale.getMessage("command.proxycmd.rate_limited")).thenReturn("one per second");
        doAnswer(invocation -> {
            invocation.<Consumer<MemberActionOutcome>>getArgument(1).accept(MemberActionOutcome.RATE_LIMITED);
            return null;
        }).when(coreLink).send(any(), any());
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);

        command.onCommand(console, mock(Command.class), "proxycmd", ARGS);

        verify(console).sendMessage("one per second");
    }
}
