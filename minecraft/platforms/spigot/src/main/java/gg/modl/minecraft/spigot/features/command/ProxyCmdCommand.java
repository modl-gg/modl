package gg.modl.minecraft.spigot.features.command;

import gg.modl.minecraft.core.network.MemberAction;
import gg.modl.minecraft.core.network.MemberActionOutcome;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.features.FeatureScheduler;
import gg.modl.minecraft.features.link.CoreLink;
import gg.modl.minecraft.features.locale.FeaturesLocaleManager;
import lombok.RequiredArgsConstructor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;

import java.util.Locale;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

@RequiredArgsConstructor
public class ProxyCmdCommand implements CommandExecutor {
    private final FeaturesLocaleManager localeManager;
    private final PluginLogger logger;
    private final FeatureScheduler scheduler;
    private final CoreLink coreLink;

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!isConsole(sender)) {
            sender.sendMessage(localeManager.getMessage("command.proxycmd.console_only"));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(localeManager.getMessage("command.proxycmd.usage"));
            return true;
        }

        if (!coreLink.isConnected()) {
            sender.sendMessage(localeManager.getMessage("command.proxycmd.not_connected"));
            return true;
        }

        String commandLine = String.join(" ", args);
        logger.info("Forwarded /" + rootOf(commandLine) + " to the proxy.");
        coreLink.send(MemberAction.proxyCommand(commandLine),
                outcome -> scheduler.runOnMainThread(() -> sender.sendMessage(reply(outcome, commandLine))));
        return true;
    }

    private static boolean isConsole(CommandSender sender) {
        return sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender;
    }

    private String reply(MemberActionOutcome outcome, String commandLine) {
        switch (outcome) {
            case ACCEPTED:
                return localeManager.getMessage("command.proxycmd.sent", mapOf("command", commandLine));
            case DENIED:
                return localeManager.getMessage("command.proxycmd.refused", mapOf("command", commandLine));
            case NOT_PERMITTED:
                return localeManager.getMessage("command.proxycmd.disabled");
            case NO_CORE:
                return localeManager.getMessage("command.proxycmd.no_answer");
            case RATE_LIMITED:
                return localeManager.getMessage("command.proxycmd.rate_limited");
            case INVALID:
                return localeManager.getMessage("command.proxycmd.invalid", mapOf("command", commandLine));
        }
        throw new IllegalArgumentException("Unknown member action outcome " + outcome);
    }

    static String rootOf(String commandLine) {
        String firstToken = commandLine.trim().split("\\s+", 2)[0];
        String withoutSlash = firstToken.startsWith("/") ? firstToken.substring(1) : firstToken;
        return withoutSlash.toLowerCase(Locale.ROOT);
    }
}
