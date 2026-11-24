package gg.modl.minecraft.bungee;

import gg.modl.minecraft.core.command.NetworkConsoleActor;
import gg.modl.minecraft.core.util.CommandUtil;
import net.md_5.bungee.api.CommandSender;
import revxrsal.commands.Lamp;
import revxrsal.commands.bungee.actor.BungeeCommandActor;

import java.util.UUID;

final class BungeeNetworkConsoleActor implements BungeeCommandActor, NetworkConsoleActor {
    private final CommandSender console;
    private final Lamp<BungeeCommandActor> lamp;

    BungeeNetworkConsoleActor(CommandSender console, Lamp<BungeeCommandActor> lamp) {
        this.console = console;
        this.lamp = lamp;
    }

    @Override
    public CommandSender sender() {
        return console;
    }

    @Override
    public UUID uniqueId() {
        return CommandUtil.CONSOLE_UUID;
    }

    @Override
    public Lamp<BungeeCommandActor> lamp() {
        return lamp;
    }
}
