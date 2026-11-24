package gg.modl.minecraft.core.staff.action;

import gg.modl.minecraft.core.network.ModerationChange;
import gg.modl.minecraft.core.util.Permissions;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

@RequiredArgsConstructor
public final class VanishActions {
    public static final StaffRequirement REQUIREMENT = StaffRequirement.builder()
            .playerOnly(true)
            .staffOnly(true)
            .permission(Permissions.MOD_ACTIONS)
            .staff2fa(true)
            .build();

    private final StaffActionContext context;

    public void toggle(StaffActor actor) {
        UUID uuid = actor.uuid();
        setVanished(actor, !context.getModeration().isVanished(uuid));
    }

    void reveal(StaffActor actor) {
        setVanished(actor, false);
    }

    private void setVanished(StaffActor actor, boolean vanish) {
        UUID uuid = actor.uuid();
        ModerationChange change = context.getModeration().setVanished(uuid, vanish);
        if (change == ModerationChange.LIMIT_REACHED) {
            context.reply(actor, "staff_mode.limit_reached");
            return;
        }
        if (!change.changed()) return;
        context.reply(actor, vanish ? "vanish.enabled" : "vanish.disabled");
        context.broadcast(vanish ? "vanish.enabled_broadcast" : "vanish.disabled_broadcast", context.displayOf(actor));
    }
}
