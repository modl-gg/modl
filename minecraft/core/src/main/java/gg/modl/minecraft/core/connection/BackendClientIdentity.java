package gg.modl.minecraft.core.connection;

import gg.modl.minecraft.core.boot.PlatformType;
import gg.modl.minecraft.core.plugin.PluginInfo;
import gg.modl.update.CompatibilityHeaders;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class BackendClientIdentity {
    String pluginVersion;
    String bootId;
    PlatformType platform;

    public static BackendClientIdentity newBoot(PlatformType platform) {
        return new BackendClientIdentity(PluginInfo.VERSION, UUID.randomUUID().toString(), platform);
    }

    Map<String, String> compatibilityHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(CompatibilityHeaders.PLUGIN_VERSION, pluginVersion);
        headers.put(CompatibilityHeaders.BOOT_ID, bootId);
        headers.put(CompatibilityHeaders.PLATFORM, platform.name());
        return headers;
    }
}
