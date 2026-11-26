package gg.modl.minecraft.core.connection;

import lombok.Builder;
import lombok.ToString;
import lombok.Value;

@Value
@Builder
public class TunnelSettings {
    public static final String CLOUDFLARE = "cloudflare";

    @Builder.Default String provider = "";
    @ToString.Exclude @Builder.Default String token = "";

    public boolean isEnabled() {
        return !provider.trim().isEmpty();
    }

    public boolean isCloudflare() {
        return CLOUDFLARE.equalsIgnoreCase(provider.trim());
    }
}
