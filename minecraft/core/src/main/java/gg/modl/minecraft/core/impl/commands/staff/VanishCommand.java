package gg.modl.minecraft.core.impl.commands.staff;

import gg.modl.minecraft.core.command.PlayerOnly;
import gg.modl.minecraft.core.command.RequiresPermission;
import gg.modl.minecraft.core.command.StaffOnly;
import gg.modl.minecraft.core.staff.action.StaffActors;
import gg.modl.minecraft.core.staff.action.VanishActions;
import gg.modl.minecraft.core.util.Permissions;
import lombok.RequiredArgsConstructor;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.command.CommandActor;

@Command("vanish") @PlayerOnly @StaffOnly @RequiredArgsConstructor
public class VanishCommand {
    private final VanishActions vanishActions;

    @Description("Toggle vanish mode")
    @RequiresPermission(Permissions.MOD_ACTIONS)
    public void onVanish(CommandActor actor) {
        vanishActions.toggle(StaffActors.of(actor));
    }
}
