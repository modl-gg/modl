package gg.modl.minecraft.spigot;

import gg.modl.minecraft.core.command.NetworkConsoleActor;
import gg.modl.minecraft.core.util.CommandUtil;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.ComponentLike;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import revxrsal.commands.Lamp;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;

import java.util.Optional;
import java.util.UUID;

final class SpigotConsoleActor implements BukkitCommandActor, NetworkConsoleActor {
    private final Lamp<BukkitCommandActor> lamp;

    SpigotConsoleActor(Lamp<BukkitCommandActor> lamp) {
        this.lamp = lamp;
    }

    @Override
    public CommandSender sender() {
        return Bukkit.getConsoleSender();
    }

    @Override
    public UUID uniqueId() {
        return CommandUtil.CONSOLE_UUID;
    }

    @Override
    public void reply(ComponentLike message) {
        audience().ifPresent(audience -> audience.sendMessage(message.asComponent()));
    }

    @Override
    public Optional<Audience> audience() {
        return Optional.empty();
    }

    @Override
    public Lamp<BukkitCommandActor> lamp() {
        return lamp;
    }
}
