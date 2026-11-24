package gg.modl.minecraft.spigot.features.command;

import gg.modl.minecraft.features.FeaturesReloadPresenter;
import lombok.RequiredArgsConstructor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

@RequiredArgsConstructor
public class ModlServerCommand implements CommandExecutor, TabCompleter {
    private static final String RELOAD_ARGUMENT = "reload";
    private static final String RELOAD_PERMISSION = "modl.server.reload";

    private final FeaturesReloadPresenter presenter;

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(RELOAD_PERMISSION)) {
            sender.sendMessage(presenter.noPermission());
            return true;
        }
        if (args.length != 1 || !RELOAD_ARGUMENT.equalsIgnoreCase(args[0])) {
            sender.sendMessage(presenter.usage());
            return true;
        }
        presenter.reload(sender::sendMessage);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission(RELOAD_PERMISSION)
                && RELOAD_ARGUMENT.startsWith(args[0].toLowerCase(Locale.ROOT))) {
            return Collections.singletonList(RELOAD_ARGUMENT);
        }
        return Collections.emptyList();
    }
}
