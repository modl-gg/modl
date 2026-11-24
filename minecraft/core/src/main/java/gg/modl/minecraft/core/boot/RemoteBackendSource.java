package gg.modl.minecraft.core.boot;

import gg.modl.minecraft.core.connection.BackendEndpoint;
import gg.modl.minecraft.core.connection.BackendEndpointResolver;
import gg.modl.minecraft.core.connection.PanelLocations;
import gg.modl.minecraft.core.connection.PlainHttpNotice;
import gg.modl.minecraft.core.connection.RemoteSettings;
import gg.modl.minecraft.core.util.PluginLogger;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

final class RemoteBackendSource implements BackendSource {
    private static final String CHECK_THREAD_NAME = "modl-remote-endpoint";
    static final Executor OWN_THREAD = task -> {
        Thread thread = new Thread(task, CHECK_THREAD_NAME);
        thread.setDaemon(true);
        thread.start();
    };

    private final BackendEndpoint endpoint;
    private final String rejectedCredentialsRemedy;
    private final PluginLogger logger;
    private final PlainHttpNotice plainHttpNotice;
    private final Executor checkExecutor;
    private @Nullable CompletableFuture<BackendEndpoint> checkedEndpoint;

    RemoteBackendSource(RemoteSettings settings, BackendEndpointResolver resolver, String configPath, PluginLogger logger) {
        this(settings, resolver, coreRemedy(configPath), logger, PlainHttpNotice.processWide(), OWN_THREAD);
    }

    RemoteBackendSource(RemoteSettings settings, BackendEndpointResolver resolver, String rejectedCredentialsRemedy,
                        PluginLogger logger, PlainHttpNotice plainHttpNotice, Executor checkExecutor) {
        this.endpoint = resolver.remote(settings, logger);
        this.rejectedCredentialsRemedy = rejectedCredentialsRemedy;
        this.logger = logger;
        this.plainHttpNotice = plainHttpNotice;
        this.checkExecutor = checkExecutor;
    }

    static String coreRemedy(String configPath) {
        return "Check remote.api-key in " + configPath + "; it must match " + PanelLocations.MINECRAFT_SERVERS + ".";
    }

    static String memberRemedy(String configPath) {
        return "Check remote.api-key in " + configPath + "; create a server key in " + PanelLocations.ADD_SERVER + ".";
    }

    @Override
    public void start() {
        beginCheck();
    }

    @Override
    public synchronized CompletableFuture<BackendEndpoint> endpoint() {
        beginCheck();
        return Objects.requireNonNull(checkedEndpoint);
    }

    private synchronized void beginCheck() {
        if (checkedEndpoint == null) checkedEndpoint = CompletableFuture.supplyAsync(this::checkPlainHttp, checkExecutor);
    }

    @Override
    public @Nullable String currentPanelUrl() {
        return null;
    }

    @Override
    public String rejectedCredentialsRemedy() {
        return rejectedCredentialsRemedy;
    }

    @Override
    public synchronized void shutdown() {
        if (checkedEndpoint != null) checkedEndpoint.cancel(false);
    }

    private BackendEndpoint checkPlainHttp() {
        plainHttpNotice.check(endpoint.baseUrl(), logger);
        return endpoint;
    }
}
