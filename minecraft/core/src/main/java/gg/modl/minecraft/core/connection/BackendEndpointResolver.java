package gg.modl.minecraft.core.connection;

import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.core.util.StringUtil;
import org.jspecify.annotations.Nullable;

public final class BackendEndpointResolver {
    static final String OVERRIDE_PROPERTY = "modl.api.url";
    static final String OVERRIDE_ENV = "MODL_API_URL";

    private final @Nullable String remoteOverride;

    BackendEndpointResolver(@Nullable String remoteOverride) {
        String trimmed = remoteOverride == null ? "" : remoteOverride.trim();
        this.remoteOverride = trimmed.isEmpty() ? null : trimmed;
    }

    public static BackendEndpointResolver fromEnvironment() {
        String property = System.getProperty(OVERRIDE_PROPERTY);
        return new BackendEndpointResolver(StringUtil.isBlank(property) ? System.getenv(OVERRIDE_ENV) : property);
    }

    public BackendEndpoint remote(RemoteSettings remote, PluginLogger logger) {
        if (remoteOverride == null) return BackendEndpoint.of(remote.getUrl(), remote.getApiKey());
        BackendEndpoint overridden;
        try {
            overridden = BackendEndpoint.of(remoteOverride, remote.getApiKey());
        } catch (IllegalArgumentException invalid) {
            throw new ConnectionConfigException("The modl server address override (-D" + OVERRIDE_PROPERTY + " or "
                    + OVERRIDE_ENV + ") is unusable: " + invalid.getMessage() + ". Fix or remove it.");
        }
        logger.info("remote.url overridden to " + overridden.baseUrl() + " (via -D" + OVERRIDE_PROPERTY + " or " + OVERRIDE_ENV + ")");
        return overridden;
    }

    public static BackendEndpoint embedded(EmbeddedSettings embedded, String internalApiKey) {
        NetworkAddress bind = embedded.bind();
        return BackendAddress.http(bind.withHost(bind.connectHost())).toEndpoint(internalApiKey);
    }
}
