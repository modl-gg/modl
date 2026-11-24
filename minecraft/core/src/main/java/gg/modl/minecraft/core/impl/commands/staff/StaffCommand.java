package gg.modl.minecraft.core.impl.commands.staff;

import gg.modl.minecraft.core.command.PlayerOnly;
import gg.modl.minecraft.core.command.StaffOnly;
import gg.modl.minecraft.core.staff.action.StaffActors;
import gg.modl.minecraft.core.staff.action.StaffMenuActions;
import lombok.RequiredArgsConstructor;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.command.CommandActor;

@RequiredArgsConstructor
public class StaffCommand {
    private final StaffMenuActions staffMenuActions;

    @Command("staffmenu")
    @Description("Open the staff menu")
    @PlayerOnly @StaffOnly
    public void staff(CommandActor actor) {
        staffMenuActions.openStaffMenu(StaffActors.of(actor));
    }
}
