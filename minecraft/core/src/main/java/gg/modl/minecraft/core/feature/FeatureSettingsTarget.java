package gg.modl.minecraft.core.feature;

public interface FeatureSettingsTarget {
    void applyFeatureSettings(FeatureSettings settings);

    FeatureStatus featureStatus();

    void reportStatusTo(Runnable statusChanged);
}
