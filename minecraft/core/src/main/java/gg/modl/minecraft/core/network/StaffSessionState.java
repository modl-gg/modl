package gg.modl.minecraft.core.network;

import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

@Value
public class StaffSessionState {
    boolean staffMode;
    boolean vanished;
    @Nullable UUID target;

    public boolean hasTarget() {
        return target != null;
    }

    boolean isEmpty() {
        return !staffMode && !vanished && target == null;
    }

    StaffSessionState withVanished(boolean vanished) {
        return new StaffSessionState(staffMode, vanished, target);
    }

    StaffSessionState withTarget(@Nullable UUID target) {
        return new StaffSessionState(staffMode, vanished, target);
    }
}
