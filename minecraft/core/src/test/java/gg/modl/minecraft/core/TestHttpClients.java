package gg.modl.minecraft.core;

import gg.modl.minecraft.api.http.ModlHttpClient;

public final class TestHttpClients {
    private TestHttpClients() {
    }

    public static HttpClientHolder connected(ModlHttpClient client) {
        HttpClientHolder holder = new HttpClientHolder();
        holder.connect(client);
        return holder;
    }
}
