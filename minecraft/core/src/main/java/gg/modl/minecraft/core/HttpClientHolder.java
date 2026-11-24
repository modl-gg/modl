package gg.modl.minecraft.core;

import gg.modl.minecraft.api.http.ModlHttpClient;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

public final class HttpClientHolder {
    private volatile @Nullable ModlHttpClient client;

    synchronized void connect(ModlHttpClient connected) {
        if (client != null) throw new IllegalStateException("modl is already connected to its server");
        client = connected;
    }

    synchronized void disconnect() {
        client = null;
    }

    public Optional<ModlHttpClient> connected() {
        return Optional.ofNullable(client);
    }

    public ModlHttpClient getClient() {
        ModlHttpClient connected = client;
        if (connected == null) throw new IllegalStateException("modl is not connected to its server");
        return connected;
    }
}
