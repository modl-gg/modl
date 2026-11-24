package gg.modl.minecraft.core.impl.commands.staff;

import gg.modl.minecraft.core.command.AdminOnly;
import gg.modl.minecraft.core.config.ConfigurationReload;
import lombok.RequiredArgsConstructor;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.command.CommandActor;

@RequiredArgsConstructor @Command("modl")
public class ModlReloadCommand {
    private final ConfigurationReload configurationReload;

    @Subcommand("reload")
    @Description("Reload modl configuration and locale files")
    @AdminOnly
    public void reload(CommandActor actor) {
        configurationReload.reload(actor::reply);
    }
}
