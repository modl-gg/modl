package gg.modl.minecraft.core.staff.action;

import gg.modl.minecraft.core.network.ModerationChange;
import gg.modl.minecraft.core.util.Permissions;
import lombok.RequiredArgsConstructor;

import java.util.UUID;
import java.util.function.BooleanSupplier;

@RequiredArgsConstructor
public final class StaffModeActions {
    public static final StaffRequirement REQUIREMENT = StaffRequirement.builder()
            .playerOnly(true)
            .staffOnly(true)
            .permission(Permissions.MOD_ACTIONS)
            .staff2fa(true)
            .build();

    private final StaffActionContext context;
    private final BooleanSupplier vanishOnEnable;

    public void toggle(StaffActor actor) {
        if (context.getModeration().isInStaffMode(actor.uuid())) disable(actor);
        else enable(actor);
    }

    public boolean enable(StaffActor actor) {
        UUID uuid = actor.uuid();
        ModerationChange change = context.getModeration().enterStaffMode(uuid, vanishOnEnable.getAsBoolean());
        if (change == ModerationChange.LIMIT_REACHED) {
            context.reply(actor, "staff_mode.limit_reached");
            return false;
        }
        if (change.changed()) {
            context.reply(actor, "staff_mode.enabled");
            context.broadcast("staff_mode.enabled_broadcast", context.displayOf(actor));
        }
        return true;
    }

    public boolean refused(StaffActor actor) {
        if (!context.getModeration().exitStaffMode(actor.uuid()).changed()) return false;
        context.broadcast("staff_mode.disabled_broadcast", context.displayOf(actor));
        return true;
    }

    public void disable(StaffActor actor) {
        if (!context.getModeration().exitStaffMode(actor.uuid()).changed()) return;
        context.reply(actor, "staff_mode.disabled");
        context.broadcast("staff_mode.disabled_broadcast", context.displayOf(actor));
    }
}
