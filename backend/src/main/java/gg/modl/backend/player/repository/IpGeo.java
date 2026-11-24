package gg.modl.backend.player.repository;

import org.jspecify.annotations.Nullable;

public record IpGeo(
    @Nullable String country,
    @Nullable String region,
    @Nullable String asn,
    boolean proxy,
    boolean hosting
) {
}
