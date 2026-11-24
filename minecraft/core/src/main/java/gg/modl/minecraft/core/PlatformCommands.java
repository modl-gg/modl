package gg.modl.minecraft.core;

import gg.modl.minecraft.core.command.NetworkConsoleActor;
import gg.modl.minecraft.core.command.error.CommandErrorCatalog;
import revxrsal.commands.Lamp;
import revxrsal.commands.command.CommandActor;

import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Consumer;

public interface PlatformCommands {
    Lamp<? extends CommandActor> buildLamp(Consumer<Lamp.Builder<? extends CommandActor>> configurator);

    CommandActor networkConsoleActor(Lamp<? extends CommandActor> lamp);

    boolean hasConsoleSender(CommandActor actor);

    default boolean isLocalConsole(CommandActor actor) {
        return !(actor instanceof NetworkConsoleActor) && hasConsoleSender(actor);
    }

    default void describeCommandErrors(CommandErrorCatalog catalog) {}

    default void finalizeLampRegistration(Lamp<? extends CommandActor> lamp) {}
    default void unregisterCommands(Lamp<? extends CommandActor> lamp) {
        lamp.unregisterAllCommands();
    }
    default Collection<String> commandNames(String label) {
        return Collections.singleton(label);
    }

    default void dispatchPlayerCommand(UUID uuid, String command) {}
    default void dispatchConsoleCommand(String command) {}
}
