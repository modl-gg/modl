package gg.modl.minecraft.core;

import gg.modl.minecraft.api.http.ImportApi;
import gg.modl.minecraft.core.connection.BackendConnections;
import gg.modl.minecraft.core.impl.http.StreamedFileUpload;
import gg.modl.minecraft.core.imports.ImportBackend;
import gg.modl.minecraft.core.imports.ImportFileUpload;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

@RequiredArgsConstructor
final class ConnectedImportBackend implements ImportBackend {
    private final HttpClientHolder httpClientHolder;
    private volatile @Nullable BackendConnections connections;

    void connect(BackendConnections connected) {
        connections = connected;
    }

    void disconnect() {
        connections = null;
    }

    @Override
    public ImportApi api() {
        return httpClientHolder.getClient();
    }

    @Override
    public ImportFileUpload newUpload() {
        BackendConnections connected = connections;
        if (connected == null) throw new IllegalStateException("modl is not connected to its server");
        return new StreamedFileUpload(connected);
    }
}
