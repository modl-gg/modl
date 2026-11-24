package gg.modl.backend.serverkey;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record ServerKey(
    String id,
    String name,
    ServerKeyScope scope,
    boolean allowProxyCommands,
    String keyHash,
    String keyHint,
    @Nullable String createdByEmail,
    Instant createdAt,
    @Nullable Instant lastSeenAt
) {
    public ServerKey withAllowProxyCommands(boolean allow) {
        return new ServerKey(id, name, scope, allow, keyHash, keyHint, createdByEmail, createdAt, lastSeenAt);
    }

    @Override
    public String toString() {
        return "ServerKey[id=" + id + ", name=" + name + ", scope=" + scope + ", allowProxyCommands=" + allowProxyCommands + "]";
    }
}
