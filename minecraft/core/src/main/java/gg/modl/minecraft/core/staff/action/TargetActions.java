package gg.modl.minecraft.core.staff.action;

import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.network.ModerationChange;
import gg.modl.minecraft.core.util.Permissions;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

@RequiredArgsConstructor
public final class TargetActions {
    public static final StaffRequirement REQUIREMENT = StaffRequirement.builder()
            .playerOnly(true)
            .staffOnly(true)
            .permission(Permissions.MOD_ACTIONS)
            .staff2fa(true)
            .build();

    private final StaffActionContext context;
    private final StaffModeActions staffModeActions;

    public void select(StaffActor actor, AbstractPlayer target) {
        UUID staff = actor.uuid();
        UUID targetUuid = target.getUuid();
        if (targetUuid.equals(staff)) {
            context.reply(actor, "target.cannot_target_self");
            return;
        }
        connectToTargetServerIfNeeded(actor, staff, targetUuid, target.getName());
        if (!staffModeActions.enable(actor)) return;
        if (context.getModeration().setTarget(staff, targetUuid) != ModerationChange.NOT_IN_STAFF_MODE) {
            context.reply(actor, "target.targeting", target.getName());
        }
    }

    public boolean clear(StaffActor actor) {
        if (!context.getModeration().clearTarget(actor.uuid()).changed()) return false;
        context.reply(actor, "target.cleared");
        return true;
    }

    private void connectToTargetServerIfNeeded(StaffActor actor, UUID staff, UUID target, String targetName) {
        Platform platform = context.getPlatform();
        String staffServer = platform.getPlayerServer(staff);
        String targetServer = platform.getPlayerServer(target);
        if (targetServer == null || targetServer.equals(staffServer)) return;
        platform.connectToServer(staff, targetServer);
        actor.reply(context.getLocaleManager().getMessage("target.connecting",
                mapOf("player", targetName, "server", targetServer)));
    }
}
