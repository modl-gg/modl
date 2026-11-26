package gg.modl.backend.infrastructure.filter;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

final class PanelContentSecurityPolicy {
    private static final String SELF = "'self'";
    private static final String[] REPLAY_ASSET_ORIGINS = {
        "https://piston-meta.mojang.com",
        "https://piston-data.mojang.com",
        "https://textures.minecraft.net"
    };

    private PanelContentSecurityPolicy() {
    }

    static String of(Set<String> objectOrigins) {
        return String.join("; ",
            "default-src " + SELF,
            "script-src " + SELF,
            "style-src " + SELF + " 'unsafe-inline'",
            "font-src " + SELF + " data: https:",
            "img-src " + sources(objectOrigins, "data:", "blob:", "https:"),
            "media-src " + sources(objectOrigins, "blob:", "https:"),
            "connect-src " + sources(objectOrigins, REPLAY_ASSET_ORIGINS),
            "worker-src " + SELF + " blob:",
            "object-src 'none'",
            "base-uri 'none'",
            "form-action " + SELF,
            "frame-ancestors 'none'");
    }

    private static String sources(Set<String> objectOrigins, String... schemes) {
        Set<String> all = new LinkedHashSet<>();
        all.add(SELF);
        all.addAll(Arrays.asList(schemes));
        all.addAll(objectOrigins);
        return String.join(" ", all);
    }
}
