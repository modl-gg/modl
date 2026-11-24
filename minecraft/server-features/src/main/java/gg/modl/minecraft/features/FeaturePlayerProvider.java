package gg.modl.minecraft.features;

import java.util.Collection;
import java.util.UUID;

public interface FeaturePlayerProvider {

    Collection<FeaturePlayer> getOnlinePlayers();

    boolean isOnline(UUID uuid);

    void dispatchConsoleCommand(String command);
}
