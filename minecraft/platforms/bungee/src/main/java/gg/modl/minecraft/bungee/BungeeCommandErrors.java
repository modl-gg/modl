package gg.modl.minecraft.bungee;

import gg.modl.minecraft.core.command.error.CommandError;
import gg.modl.minecraft.core.command.error.CommandErrorCatalog;
import revxrsal.commands.bungee.exception.InvalidPlayerException;
import revxrsal.commands.bungee.exception.SenderNotPlayerException;

final class BungeeCommandErrors {
    private BungeeCommandErrors() {
    }

    static void addTo(CommandErrorCatalog catalog) {
        catalog.explain(SenderNotPlayerException.class, e -> CommandError.playersOnly());
        catalog.explain(InvalidPlayerException.class, e -> CommandError.invalidPlayer(e.input()));
    }
}
