package gg.modl.minecraft.core.staff.action;

import revxrsal.commands.command.CommandActor;

import java.util.UUID;

public interface StaffActor {
    UUID uuid();

    boolean isConsole();

    void reply(String message);

    CommandActor commandActor();
}
