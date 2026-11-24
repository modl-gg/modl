package gg.modl.minecraft.core.impl.commands.staff;

import gg.modl.minecraft.core.command.PlayerOnly;
import gg.modl.minecraft.core.command.RequiresPermission;
import gg.modl.minecraft.core.command.StaffOnly;
import gg.modl.minecraft.core.staff.action.StaffActors;
import gg.modl.minecraft.core.staff.action.StaffModeActions;
import gg.modl.minecraft.core.util.Permissions;
import lombok.RequiredArgsConstructor;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.command.CommandActor;

@Command("staffmode") @PlayerOnly @StaffOnly @RequiredArgsConstructor
public class StaffModeCommand {
    private final StaffModeActions staffModeActions;

    @Description("Toggle staff mode")
    @RequiresPermission(Permissions.MOD_ACTIONS)
    public void onStaffMode(CommandActor actor) {
        staffModeActions.toggle(StaffActors.of(actor));
    }
}
