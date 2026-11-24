package gg.modl.minecraft.core.staff.action;

import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.core.network.ModerationChange;
import gg.modl.minecraft.core.network.NetworkAvailability;
import gg.modl.minecraft.core.staff.StaffCommandUtil.StaffDisplay;
import gg.modl.minecraft.core.util.CommandUtil;
import gg.modl.minecraft.core.util.Permissions;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

@RequiredArgsConstructor
public final class FreezeActions {
    public static final StaffRequirement REQUIREMENT = StaffRequirement.builder()
            .staffOnly(true)
            .permission(Permissions.MOD_ACTIONS)
            .staff2fa(true)
            .build();

    private final StaffActionContext context;
    private final NetworkAvailability networkAvailability;

    public void toggle(StaffActor actor, AbstractPlayer target) {
        UUID targetUuid = target.getUuid();
        String targetName = target.getName();
        if (!context.getPlatform().isOnline(targetUuid)) {
            context.reply(actor, "freeze.target_offline", targetName);
            return;
        }
        if (context.getModeration().isFrozen(targetUuid)) unfreeze(actor, targetUuid, targetName);
        else freeze(actor, targetUuid, targetName);
    }

    private void unfreeze(StaffActor actor, UUID targetUuid, String targetName) {
        if (!context.getModeration().unfreeze(targetUuid).changed()) return;
        context.broadcast("freeze.staff_notification_unfreeze", context.displayOf(actor), targetName);
    }

    private void freeze(StaffActor actor, UUID targetUuid, String targetName) {
        UUID staff = actor.isConsole() ? CommandUtil.CONSOLE_UUID : actor.uuid();
        ModerationChange change = context.getModeration().freeze(targetUuid, staff);
        if (change == ModerationChange.LIMIT_REACHED) {
            context.reply(actor, "freeze.limit_reached");
            return;
        }
        if (!change.changed()) return;
        StaffDisplay display = context.displayOf(actor);
        context.broadcast("freeze.staff_notification_freeze", display, targetName);
        String targetServer = context.getPlatform().getPlayerServer(targetUuid);
        if (!networkAvailability.isEnforcedOn(targetServer)) context.reply(actor, "freeze.not_enforced", targetName);
    }
}
