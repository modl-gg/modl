package gg.modl.minecraft.spigot.boot;

import gg.modl.minecraft.spigot.features.ServerFeaturesComponent;
import org.jspecify.annotations.Nullable;

public final class PolarHookLatch {
    private @Nullable ServerFeaturesComponent runningFeatures;
    private boolean polarEnabled;

    public synchronized void polarEnabled() {
        polarEnabled = true;
        ServerFeaturesComponent features = runningFeatures;
        if (features != null) features.hookPolar();
    }

    public synchronized void featuresStarted(ServerFeaturesComponent features) {
        runningFeatures = features;
        if (polarEnabled) features.hookPolar();
    }

    public synchronized void featuresStopped() {
        runningFeatures = null;
    }
}
