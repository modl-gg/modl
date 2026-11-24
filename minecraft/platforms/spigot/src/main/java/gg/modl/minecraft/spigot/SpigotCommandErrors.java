package gg.modl.minecraft.spigot;

import gg.modl.minecraft.core.command.error.CommandError;
import gg.modl.minecraft.core.command.error.CommandErrorCatalog;
import revxrsal.commands.bukkit.exception.EmptyEntitySelectorException;
import revxrsal.commands.bukkit.exception.InvalidPlayerException;
import revxrsal.commands.bukkit.exception.InvalidWorldException;
import revxrsal.commands.bukkit.exception.MalformedEntitySelectorException;
import revxrsal.commands.bukkit.exception.MissingLocationParameterException;
import revxrsal.commands.bukkit.exception.MoreThanOneEntityException;
import revxrsal.commands.bukkit.exception.NonPlayerEntitiesException;
import revxrsal.commands.bukkit.exception.SenderNotConsoleException;
import revxrsal.commands.bukkit.exception.SenderNotPlayerException;

import java.util.Locale;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

final class SpigotCommandErrors {
    private SpigotCommandErrors() {
    }

    static void addTo(CommandErrorCatalog catalog) {
        catalog.explain(SenderNotPlayerException.class, e -> CommandError.playersOnly());
        catalog.explain(SenderNotConsoleException.class, e -> CommandError.consoleOnly());
        catalog.explain(InvalidPlayerException.class, e -> CommandError.invalidPlayer(e.input()));
        catalog.explain(InvalidWorldException.class, e -> CommandError.of("invalid_world", mapOf("input", e.input())));
        catalog.explain(MissingLocationParameterException.class, e -> CommandError.of("missing_coordinate",
                mapOf("axis", e.axis().name().toLowerCase(Locale.ROOT))));
        catalog.explain(MalformedEntitySelectorException.class,
                e -> CommandError.of("invalid_selector", mapOf("input", e.input())));
        catalog.explain(NonPlayerEntitiesException.class,
                e -> CommandError.of("selector_players_only", mapOf("input", e.input())));
        catalog.explain(MoreThanOneEntityException.class, e -> CommandError.of("selector_too_many"));
        catalog.explain(EmptyEntitySelectorException.class, e -> CommandError.of("selector_empty"));
    }
}
