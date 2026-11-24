package gg.modl.minecraft.core.service.sync;

import org.jspecify.annotations.Nullable;

public interface StatWipeExecutor {
    void executeStatWipe(String username, String uuid, String punishmentId, StatWipeCallback callback);

    interface StatWipeCallback {
        void onComplete(StatWipeOutcome outcome, @Nullable String serverName);
    }
}
