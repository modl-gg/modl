package gg.modl.minecraft.core.staff.action;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

@RequiredArgsConstructor
public final class StaffSessionRevocation {
    private final StaffActionContext context;
    private final StaffActionGate gate;
    private final StaffModeActions staffModeActions;
    private final VanishActions vanishActions;

    public void endUnauthorizedSessions() {
        for (UUID staff : context.getModeration().staffWithSessions()) {
            StaffActor actor = StaffActors.player(staff, context.getPlatform());
            if (gate.allows(actor, StaffModeActions.REQUIREMENT)) continue;
            if (context.getModeration().isInStaffMode(staff)) staffModeActions.disable(actor);
            else if (context.getModeration().isVanished(staff)) vanishActions.reveal(actor);
        }
    }
}
