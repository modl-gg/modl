package gg.modl.backend.tunnel.cloudflared;

import java.net.URI;

public record CloudflaredBuild(String version, HostPlatform platform, long size, String sha256, URI source) {
    public String file() {
        return source.getPath().substring(source.getPath().lastIndexOf('/') + 1);
    }
}
