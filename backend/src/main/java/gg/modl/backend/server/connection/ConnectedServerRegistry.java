package gg.modl.backend.server.connection;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConnectedServerRegistry {
    static final Duration EXPIRY = Duration.ofMinutes(10);
    static final int CAPACITY = 64;

    private final Clock clock;
    private final Map<String, ConnectedServer> servers = new LinkedHashMap<>();

    public synchronized void recordStartup(String instanceId, ServerStartupReport report) {
        Instant now = clock.instant();
        makeRoomFor(instanceId, now);
        servers.put(instanceId, ConnectedServer.started(instanceId, report, now));
    }

    public synchronized void recordHeartbeat(ServerHeartbeat heartbeat) {
        if (heartbeat.instanceId().isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        ConnectedServer known = servers.get(heartbeat.instanceId());
        if (known != null) {
            servers.put(heartbeat.instanceId(), known.withHeartbeat(heartbeat, now));
            return;
        }
        makeRoomFor(heartbeat.instanceId(), now);
        servers.put(heartbeat.instanceId(), ConnectedServer.firstHeartbeat(heartbeat, now));
    }

    public synchronized List<ConnectedServer> connected() {
        removeExpired(clock.instant());
        return List.copyOf(servers.values());
    }

    private void makeRoomFor(String instanceId, Instant now) {
        removeExpired(now);
        if (servers.containsKey(instanceId) || servers.size() < CAPACITY) {
            return;
        }
        servers.values().stream()
            .min(Comparator.comparing(ConnectedServer::lastSeen))
            .ifPresent(oldest -> servers.remove(oldest.instanceId()));
    }

    private void removeExpired(Instant now) {
        Instant oldestAllowed = now.minus(EXPIRY);
        servers.values().removeIf(server -> server.lastSeen().isBefore(oldestAllowed));
    }
}
