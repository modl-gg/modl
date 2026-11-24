package gg.modl.minecraft.core.boot;

import gg.modl.minecraft.core.connection.BackendEndpoint;
import gg.modl.minecraft.core.connection.BackendEndpointResolver;
import gg.modl.minecraft.core.connection.ConnectionConfig;
import gg.modl.minecraft.core.util.PluginLogger;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public interface BackendSource {
    void start();

    CompletableFuture<BackendEndpoint> endpoint();

    @Nullable String currentPanelUrl();

    String rejectedCredentialsRemedy();

    void shutdown();

    static BackendSource forConfig(ConnectionConfig config, Path dataFolder, String configPath, PluginLogger logger) {
        switch (config.getMode()) {
            case EMBEDDED:
                return new EmbeddedBackendSource(config.getEmbedded(), dataFolder, configPath, logger);
            case REMOTE:
                return new RemoteBackendSource(config.getRemote(), BackendEndpointResolver.fromEnvironment(), configPath, logger);
            default:
                throw new IllegalArgumentException("mode " + config.getMode().yamlName() + " does not connect to a modl server");
        }
    }
}
