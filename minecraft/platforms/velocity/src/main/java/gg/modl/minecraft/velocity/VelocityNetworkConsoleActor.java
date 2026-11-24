package gg.modl.minecraft.velocity;

import com.velocitypowered.api.command.CommandSource;
import gg.modl.minecraft.core.command.NetworkConsoleActor;
import revxrsal.commands.Lamp;
import revxrsal.commands.velocity.actor.VelocityCommandActor;

final class VelocityNetworkConsoleActor extends SectionCodeVelocityActor implements NetworkConsoleActor {
    VelocityNetworkConsoleActor(CommandSource console, Lamp<VelocityCommandActor> lamp) {
        super(console, lamp);
    }
}
