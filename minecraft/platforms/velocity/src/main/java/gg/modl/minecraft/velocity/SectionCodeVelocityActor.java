package gg.modl.minecraft.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import gg.modl.minecraft.core.util.CommandUtil;
import net.kyori.adventure.text.ComponentLike;
import revxrsal.commands.Lamp;
import revxrsal.commands.velocity.actor.VelocityCommandActor;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

class SectionCodeVelocityActor implements VelocityCommandActor {
    private static final String ERROR_COLOR = "&c";

    private final CommandSource source;
    private final Lamp<VelocityCommandActor> lamp;

    SectionCodeVelocityActor(CommandSource source, Lamp<VelocityCommandActor> lamp) {
        this.source = source;
        this.lamp = lamp;
    }

    @Override
    public final CommandSource source() {
        return source;
    }

    @Override
    public final UUID uniqueId() {
        if (source instanceof Player player) return player.getUniqueId();
        if (source instanceof ConsoleCommandSource) return CommandUtil.CONSOLE_UUID;
        return UUID.nameUUIDFromBytes(name().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public final void reply(ComponentLike message) {
        sendRawMessage(message);
    }

    @Override
    public final void error(ComponentLike message) {
        sendRawError(message);
    }

    @Override
    public final void sendRawMessage(String message) {
        sendRawMessage(Colors.get(message));
    }

    @Override
    public final void sendRawError(String message) {
        sendRawMessage(Colors.get(ERROR_COLOR + message));
    }

    @Override
    public final Lamp<VelocityCommandActor> lamp() {
        return lamp;
    }
}
