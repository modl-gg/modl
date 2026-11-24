package gg.modl.minecraft.core.impl.commands.staff;

import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.core.command.PlayerOnly;
import gg.modl.minecraft.core.command.RequiresPermission;
import gg.modl.minecraft.core.command.StaffOnly;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.staff.action.StaffActor;
import gg.modl.minecraft.core.staff.action.StaffActors;
import gg.modl.minecraft.core.staff.action.TargetActions;
import gg.modl.minecraft.core.util.Permissions;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.command.CommandActor;

@Command("target") @PlayerOnly @StaffOnly @RequiredArgsConstructor
public class TargetCommand {
    private final TargetActions targetActions;
    private final LocaleManager localeManager;

    @Description("Target a player for moderation")
    @RequiresPermission(Permissions.MOD_ACTIONS)
    public void onTarget(CommandActor actor, @Optional @Nullable AbstractPlayer target) {
        StaffActor staff = StaffActors.of(actor);
        if (target != null) {
            targetActions.select(staff, target);
        } else if (!targetActions.clear(staff)) {
            actor.reply(localeManager.getMessage("target.usage"));
        }
    }
}
