package gg.modl.backend.realtime.state;

import gg.modl.backend.realtime.auth.RealtimeClientKind;
import gg.modl.backend.realtime.auth.RealtimePrincipal;
import gg.modl.proto.modl.v1.Topic;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class RealtimeConnectionState {
    private final String connectionId = UUID.randomUUID().toString();
    private final Instant connectedAt = Instant.now();
    private final Set<Topic> subscriptions = EnumSet.noneOf(Topic.class);
    private final RealtimeOutbox outbox;
    private final AtomicLong deliveryAttempts = new AtomicLong();
    private final AtomicLong deliveryFailures = new AtomicLong();
    private final AtomicLong outboundHeartbeatSequence = new AtomicLong();
    private volatile Instant lastHeartbeat = Instant.now();
    private volatile @Nullable RealtimePrincipal principal;
    private volatile boolean closing;
    private volatile @Nullable Instant terminalSince;

    public RealtimeConnectionState(RealtimeOutbox outbox) {
        this.outbox = outbox;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }

    public boolean isAuthenticated() {
        return principal != null;
    }

    public void authenticate(RealtimePrincipal principal) {
        this.principal = principal;
    }

    public boolean isClosing() {
        return closing;
    }

    public void markClosing() {
        closing = true;
    }

    public void markTerminal() {
        if (terminalSince == null) {
            terminalSince = Instant.now();
        }
    }

    @Nullable
    public Instant getTerminalSince() {
        return terminalSince;
    }

    public RealtimePrincipal getPrincipal() {
        RealtimePrincipal authenticated = principal;
        if (authenticated == null) {
            throw new IllegalStateException("Realtime connection " + connectionId + " has no principal before its ClientHello");
        }
        return authenticated;
    }

    @Nullable
    public RealtimeClientKind getClientKind() {
        RealtimePrincipal authenticated = principal;
        return authenticated != null ? authenticated.clientKind() : null;
    }

    @Nullable
    public String getTenantId() {
        return getServerId();
    }

    @Nullable
    public String getServerId() {
        RealtimePrincipal authenticated = principal;
        return authenticated != null ? authenticated.serverId() : null;
    }

    public Set<Topic> getSubscriptions() {
        synchronized (subscriptions) {
            return Set.copyOf(subscriptions);
        }
    }

    public void subscribe(Topic topic) {
        synchronized (subscriptions) {
            subscriptions.add(topic);
        }
    }

    public void unsubscribe(Topic topic) {
        synchronized (subscriptions) {
            subscriptions.remove(topic);
        }
    }

    public boolean isSubscribedTo(Topic topic) {
        synchronized (subscriptions) {
            return subscriptions.contains(topic);
        }
    }

    public Instant getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void recordHeartbeat() {
        lastHeartbeat = Instant.now();
    }

    /** Sequence for the next unsolicited server -> client heartbeat on this connection. */
    public long nextOutboundHeartbeatSequence() {
        return outboundHeartbeatSequence.incrementAndGet();
    }

    public RealtimeOutbox outbox() {
        return outbox;
    }

    public long recordDeliveryAttempt() {
        return deliveryAttempts.incrementAndGet();
    }

    public long recordDeliveryFailure() {
        return deliveryFailures.incrementAndGet();
    }
}
