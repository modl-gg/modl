package gg.modl.minecraft.velocity;

import gg.modl.minecraft.core.command.error.CommandError;
import gg.modl.minecraft.core.command.error.CommandErrorCatalog;
import revxrsal.commands.velocity.exception.InvalidPlayerException;
import revxrsal.commands.velocity.exception.SenderNotConsoleException;
import revxrsal.commands.velocity.exception.SenderNotPlayerException;

final class VelocityCommandErrors {
    private VelocityCommandErrors() {
    }

    static void addTo(CommandErrorCatalog catalog) {
        catalog.explain(SenderNotPlayerException.class, e -> CommandError.playersOnly());
        catalog.explain(SenderNotConsoleException.class, e -> CommandError.consoleOnly());
        catalog.explain(InvalidPlayerException.class, e -> CommandError.invalidPlayer(e.input()));
    }
}
