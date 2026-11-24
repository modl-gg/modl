package gg.modl.minecraft.core.integration.mojang;

import org.jspecify.annotations.Nullable;

public final class MojangProfiles {
    private static volatile @Nullable MojangProfileClient client;

    private MojangProfiles() {}

    public static MojangProfileClient client() {
        MojangProfileClient local = client;
        if (local == null) {
            synchronized (MojangProfiles.class) {
                local = client;
                if (local == null) {
                    local = new MojangProfileClient();
                    client = local;
                }
            }
        }
        return local;
    }

    public static void shutdown() {
        MojangProfileClient local;
        synchronized (MojangProfiles.class) {
            local = client;
            client = null;
        }
        if (local != null) local.shutdown();
    }
}
