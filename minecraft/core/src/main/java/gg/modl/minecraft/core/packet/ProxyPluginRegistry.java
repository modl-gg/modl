package gg.modl.minecraft.core.packet;

import org.jspecify.annotations.Nullable;

public interface ProxyPluginRegistry {
    @Nullable String versionOf(String pluginId);

    default boolean isPresent(String pluginId) {
        return versionOf(pluginId) != null;
    }
}
