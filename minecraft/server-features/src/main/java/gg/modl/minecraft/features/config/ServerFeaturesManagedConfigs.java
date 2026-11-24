package gg.modl.minecraft.features.config;

import gg.modl.minecraft.core.config.yaml.ConfigSchema;
import gg.modl.minecraft.core.config.yaml.ManagedConfig;

public final class ServerFeaturesManagedConfigs {
    public static final ManagedConfig SERVER_FEATURES = ManagedConfig.of("server-features.yml",
            ConfigSchema.withDynamicSections("report-violation-threshold"));

    public static final ManagedConfig STAFF_MODE = ManagedConfig.of("staff_mode.yml",
            ConfigSchema.withDynamicSections("staff_hotbar", "target_hotbar"));

    private ServerFeaturesManagedConfigs() {
    }
}
