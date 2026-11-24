package gg.modl.backend.instance;

import gg.modl.backend.serverkey.ServerKey;
import gg.modl.backend.serverkey.ServerKeyScope;
import org.jspecify.annotations.Nullable;

public record MinecraftCredential(ServerKeyScope scope, @Nullable String keyId, @Nullable String keyName, Source source) {
    private static final MinecraftCredential INSTANCE_KEY =
        new MinecraftCredential(ServerKeyScope.FULL, null, null, Source.INSTANCE_KEY);
    private static final MinecraftCredential INTERNAL_KEY =
        new MinecraftCredential(ServerKeyScope.FULL, null, null, Source.INTERNAL_KEY);

    public MinecraftCredential(ServerKeyScope scope, @Nullable String keyId, @Nullable String keyName) {
        this(scope, keyId, keyName, keyId == null ? Source.INSTANCE_KEY : Source.SERVER_KEY);
    }

    public static MinecraftCredential instanceKey() {
        return INSTANCE_KEY;
    }

    public static MinecraftCredential internalKey() {
        return INTERNAL_KEY;
    }

    public static MinecraftCredential of(ServerKey key) {
        return new MinecraftCredential(key.scope(), key.id(), key.name(), Source.SERVER_KEY);
    }

    public boolean isMemberOnly() {
        return scope.isMemberOnly();
    }

    public boolean isServerKey() {
        return keyId != null;
    }

    public String rateLimitIdentity() {
        return source == Source.SERVER_KEY ? "key:" + keyId : source.name();
    }

    public enum Source {
        INSTANCE_KEY,
        INTERNAL_KEY,
        SERVER_KEY
    }
}
