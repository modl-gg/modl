package gg.modl.minecraft.core.network;

import lombok.Value;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Value
public class LeftNetworkOutcome {
    boolean frozen;
    boolean vanished;
    boolean inStaffMode;
    List<UUID> staffWhoseTargetLeft;

    public LeftNetworkOutcome(boolean frozen, boolean vanished, boolean inStaffMode, List<UUID> staffWhoseTargetLeft) {
        this.frozen = frozen;
        this.vanished = vanished;
        this.inStaffMode = inStaffMode;
        this.staffWhoseTargetLeft = Collections.unmodifiableList(staffWhoseTargetLeft);
    }

    boolean changedAnything() {
        return frozen || vanished || inStaffMode || !staffWhoseTargetLeft.isEmpty();
    }
}
