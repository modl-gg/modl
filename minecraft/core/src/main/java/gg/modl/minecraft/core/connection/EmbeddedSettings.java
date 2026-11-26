package gg.modl.minecraft.core.connection;

import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class EmbeddedSettings {
    public static final int DEFAULT_PORT = 8080;
    public static final String DEFAULT_BIND_ADDRESS = "0.0.0.0";
    public static final String TRUSTED_PROXY_PRIVATE = "private";
    public static final String TRUSTED_PROXY_LOOPBACK = "loopback";
    public static final String LOOPBACK_BIND_ADDRESS = "127.0.0.1";

    @Builder.Default int port = DEFAULT_PORT;
    @Builder.Default String bindAddress = DEFAULT_BIND_ADDRESS;
    @Builder.Default String publicUrl = "";
    @Builder.Default String adminEmail = "";
    @Builder.Default boolean trustProxyHeaders = false;
    @Builder.Default String trustedProxy = TRUSTED_PROXY_PRIVATE;
    @Builder.Default String downloadUrl = "";
    @Builder.Default DatabaseSettings database = DatabaseSettings.builder().build();
    @Builder.Default AiSettings ai = AiSettings.builder().build();
    @Builder.Default SmtpSettings smtp = SmtpSettings.builder().build();
    @Builder.Default TunnelSettings tunnel = TunnelSettings.builder().build();

    public NetworkAddress bind() {
        return NetworkAddress.of(bindAddress, port);
    }

    public boolean trustsOnlyLoopbackProxies() {
        return TRUSTED_PROXY_LOOPBACK.equalsIgnoreCase(trustedProxy.trim());
    }

    public boolean hasPublicUrl() {
        return !publicUrl.trim().isEmpty();
    }

    public EmbeddedSettings withTunnelNetworkDefaults() {
        if (!tunnel.isEnabled()) return this;
        boolean defaultBind = DEFAULT_BIND_ADDRESS.equals(bindAddress.trim());
        return toBuilder()
                .bindAddress(defaultBind ? LOOPBACK_BIND_ADDRESS : bindAddress)
                .trustProxyHeaders(true)
                .trustedProxy(TRUSTED_PROXY_LOOPBACK)
                .build();
    }
}
