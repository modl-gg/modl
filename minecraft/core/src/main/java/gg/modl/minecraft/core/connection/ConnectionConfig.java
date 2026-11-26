package gg.modl.minecraft.core.connection;

import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class ConnectionConfig {
    public static final String FILE_NAME = "connection.yml";

    @Builder.Default ConnectionMode mode = ConnectionMode.EMBEDDED;
    @Builder.Default EmbeddedSettings embedded = EmbeddedSettings.builder().build();
    @Builder.Default RemoteSettings remote = RemoteSettings.builder().build();

    public ConnectionConfig withTunnelNetworkDefaults() {
        return toBuilder().embedded(embedded.withTunnelNetworkDefaults()).build();
    }
}
