package gg.modl.minecraft.features.freeze;

import java.util.UUID;

public interface FreezeOps {
    void sendMessage(UUID uuid, String message);

    void onFrozen(UUID target);

    void onUnfrozen(UUID target);
}
