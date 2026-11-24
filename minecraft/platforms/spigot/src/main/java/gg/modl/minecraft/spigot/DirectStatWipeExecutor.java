package gg.modl.minecraft.spigot;

import gg.modl.minecraft.core.service.sync.StatWipeExecutor;
import gg.modl.minecraft.core.service.sync.StatWipeOutcome;
import gg.modl.minecraft.spigot.features.ServerFeaturesComponent;
import gg.modl.minecraft.features.statwipe.StatWipeHandler;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class DirectStatWipeExecutor implements StatWipeExecutor {
    private final ServerFeaturesComponent featuresComponent;
    private final String serverName;

    @Override
    public void executeStatWipe(String username, String uuid, String punishmentId, StatWipeCallback callback) {
        dispatchStatWipeOnMainThread(featuresComponent.getStatWipeHandler(), username, uuid, punishmentId, callback);
    }

    private void dispatchStatWipeOnMainThread(StatWipeHandler handler, String username, String uuid,
                                              String punishmentId, StatWipeCallback callback) {
        featuresComponent.getScheduler().runOnMainThread(() -> {
            boolean success = handler.execute(username, uuid, punishmentId);
            callback.onComplete(success ? StatWipeOutcome.EXECUTED : StatWipeOutcome.FAILED, serverName);
        });
    }
}
