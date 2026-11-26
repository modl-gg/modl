package gg.modl.backend.network;

import gg.modl.backend.realtime.auth.RealtimePrincipal;
import gg.modl.backend.realtime.state.RealtimeConnectionState;
import gg.modl.proto.modl.v1.ServerRole;
import gg.modl.proto.modl.v1.Topic;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.web.socket.WebSocketSession;

public final class NetworkSession {
    private final WebSocketSession socket;
    private final RealtimeConnectionState state;
    private final RealtimePrincipal principal;
    private final Instant connectedAt;
    private final AtomicReference<@Nullable Boolean> proxyCommandsGrant = new AtomicReference<>();
    private volatile int onlinePlayers;
    private volatile List<ProxyServer> proxyServers = List.of();
    private volatile @Nullable FeatureStatus features;
    private volatile boolean welcomed;

    NetworkSession(WebSocketSession socket, RealtimeConnectionState state, RealtimePrincipal principal, Instant connectedAt) {
        this.socket = socket;
        this.state = state;
        this.principal = principal;
        this.connectedAt = connectedAt;
    }

    public String connectionId() {
        return state.getConnectionId();
    }

    public String instanceId() {
        return principal.serverInstanceId() != null ? principal.serverInstanceId() : state.getConnectionId();
    }

    public String name() {
        return principal.serverName() != null ? principal.serverName() : "";
    }

    boolean isNamed(String serverName) {
        return !name().isEmpty() && name().toLowerCase(Locale.ROOT).equals(serverName.toLowerCase(Locale.ROOT));
    }

    CoreIdentity coreIdentity() {
        return new CoreIdentity(keyId(), name().toLowerCase(Locale.ROOT));
    }

    public ServerRole role() {
        return principal.role();
    }

    public boolean isMember() {
        return principal.isNetworkMember();
    }

    public boolean isCore() {
        return principal.isNetworkCore();
    }

    public boolean holdsFullCredential() {
        return principal.holdsFullCredential();
    }

    @Nullable
    public String keyId() {
        return principal.credential() != null ? principal.credential().keyId() : null;
    }

    @Nullable
    public String keyName() {
        return principal.credential() != null ? principal.credential().keyName() : null;
    }

    public String pluginVersion() {
        return principal.pluginVersion() != null ? principal.pluginVersion() : "";
    }

    public boolean subscribedTo(Topic topic) {
        return state.isSubscribedTo(topic);
    }

    public boolean isOpen() {
        return welcomed && !isEnded();
    }

    boolean isEnded() {
        return !socket.isOpen() || state.isClosing();
    }

    void welcome() {
        welcomed = true;
    }

    public boolean proxyCommandsGranted() {
        return holdsFullCredential() || Boolean.TRUE.equals(proxyCommandsGrant.get());
    }

    void initializeProxyCommandsGrant(boolean granted) {
        proxyCommandsGrant.compareAndSet(null, granted);
    }

    void changeProxyCommandsGrant(boolean granted) {
        proxyCommandsGrant.set(granted);
    }

    public int onlinePlayers() {
        return onlinePlayers;
    }

    boolean reportOnlinePlayers(int count) {
        int previous = onlinePlayers;
        onlinePlayers = count;
        return previous != count;
    }

    void reportProxyServers(List<ProxyServer> servers) {
        proxyServers = List.copyOf(servers);
    }

    void reportFeatures(FeatureStatus reported) {
        features = reported;
    }

    public Instant connectedAt() {
        return connectedAt;
    }

    WebSocketSession socket() {
        return socket;
    }

    RealtimeConnectionState state() {
        return state;
    }

    LiveNetworkSession toLiveView() {
        return new LiveNetworkSession(instanceId(), name(), role(), keyName(), pluginVersion(), onlinePlayers, connectedAt,
            proxyServers, features);
    }
}
