package gg.modl.backend.iplookup.model;

import org.jspecify.annotations.Nullable;

public record IpFacts(
    @Nullable String country,
    @Nullable String region,
    @Nullable String asn,
    VpnStatus vpn,
    boolean hosting
) {
}
