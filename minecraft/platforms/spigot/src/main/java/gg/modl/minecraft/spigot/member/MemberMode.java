package gg.modl.minecraft.spigot.member;

import gg.modl.minecraft.api.http.request.StartupRequest;
import gg.modl.minecraft.core.boot.MemberConnection;
import gg.modl.minecraft.core.boot.PlatformType;
import gg.modl.minecraft.core.connection.BackendClientIdentity;
import gg.modl.minecraft.core.connection.ConnectionConfig;
import gg.modl.minecraft.core.member.MemberSession;
import gg.modl.minecraft.core.member.MemberSessionListener;
import gg.modl.minecraft.core.network.link.NetworkMemberLink;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.core.connection.RemoteSettings;
import gg.modl.minecraft.features.AbstractServerFeatures;
import gg.modl.minecraft.features.FeaturesReloadStep;
import gg.modl.minecraft.features.config.ServerFeaturesConfig;
import gg.modl.minecraft.spigot.boot.PolarHookLatch;
import gg.modl.minecraft.spigot.boot.TicketCreatorFactory;
import gg.modl.minecraft.spigot.features.ServerFeaturesComponent;
import gg.modl.minecraft.spigot.features.SpigotFeaturesResources;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.util.function.Supplier;

public final class MemberMode {
    static final String REJECTED_KEY_REASON = "the modl server rejected the server key";

    private final ServerFeaturesComponent features;
    private final MemberConnection connection;
    private final PolarHookLatch polarHooks;
    private final NetworkLinkSessions sessions;

    private MemberMode(ServerFeaturesComponent features, MemberConnection connection, PolarHookLatch polarHooks,
                       NetworkLinkSessions sessions) {
        this.features = features;
        this.connection = connection;
        this.polarHooks = polarHooks;
        this.sessions = sessions;
    }

    public static MemberMode enable(JavaPlugin plugin, ConnectionConfig config, String configPath,
                                    StartupRequest request, PluginLogger logger, boolean polarLoaderAvailable,
                                    PolarHookLatch polarHooks, Supplier<RemoteSettings> reloadedRemoteSettings) {
        ServerFeaturesConfig featuresConfig = AbstractServerFeatures.loadConfig(new SpigotFeaturesResources(plugin), logger);
        ServerFeaturesComponent features = new ServerFeaturesComponent(plugin, featuresConfig, logger, polarLoaderAvailable);
        boolean debug = plugin.getConfig().getBoolean("debug", false);
        NetworkLinkSessions sessions = new NetworkLinkSessions(features);
        MemberConnection connection = MemberConnection.create(config.getRemote(), configPath, request,
                BackendClientIdentity.newBoot(PlatformType.SPIGOT), debug, logger, sessions);
        MemberMode mode = new MemberMode(features, connection, polarHooks, sessions);
        try {
            LinkedCoreLink coreLink = new LinkedCoreLink();
            features.enableMember(TicketCreatorFactory.standalone(plugin, connection::httpClient),
                    coreLink, featuresConfig::getServerName);
            NetworkMemberLink link = NetworkMemberLink.builder()
                    .moderation(features.getModerationReconciler())
                    .captures(features.memberReplayCaptures())
                    .statWipes(features.memberStatWipes(connection::httpClient, featuresConfig::getServerName))
                    .onlinePlayerCount(features::onlinePlayerCount)
                    .serverName(featuresConfig::getServerName)
                    .credentialsRejected(() -> connection.restart(REJECTED_KEY_REASON))
                    .configPath(configPath)
                    .logger(logger)
                    .debugMode(debug)
                    .build();
            coreLink.use(link);
            sessions.use(link);
            polarHooks.featuresStarted(features);
            features.addReloadStep(new ConnectionReload(connection, reloadedRemoteSettings));
            connection.start();
        } catch (RuntimeException | LinkageError failure) {
            mode.disable();
            throw failure;
        }
        return mode;
    }

    public void disable() {
        connection.shutdown();
        sessions.shutdown();
        polarHooks.featuresStopped();
        features.disable();
    }

    private static final class ConnectionReload implements FeaturesReloadStep {
        private final MemberConnection connection;
        private final Supplier<RemoteSettings> reloadedRemoteSettings;

        private ConnectionReload(MemberConnection connection, Supplier<RemoteSettings> reloadedRemoteSettings) {
            this.connection = connection;
            this.reloadedRemoteSettings = reloadedRemoteSettings;
        }

        @Override
        public String name() {
            return "connection.yml";
        }

        @Override
        public void reload() {
            connection.reload(reloadedRemoteSettings.get());
        }
    }

    private static final class NetworkLinkSessions implements MemberSessionListener {
        private final ServerFeaturesComponent features;
        private volatile @Nullable NetworkMemberLink link;

        private NetworkLinkSessions(ServerFeaturesComponent features) {
            this.features = features;
        }

        private void use(NetworkMemberLink link) {
            this.link = link;
        }

        private void shutdown() {
            NetworkMemberLink current = link;
            if (current != null) current.shutdown();
        }

        @Override
        public void onConnected(MemberSession session) {
            features.uploadReplaysTo(session.getConnections());
            NetworkMemberLink current = link;
            if (current != null) current.connect(session);
        }

        @Override
        public void onDisconnected(MemberSession session) {
            NetworkMemberLink current = link;
            if (current != null) current.disconnect();
        }

        @Override
        public void onDegraded(String reason) {
        }
    }
}
