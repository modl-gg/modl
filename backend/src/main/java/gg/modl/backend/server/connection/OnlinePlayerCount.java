package gg.modl.backend.server.connection;

import gg.modl.proto.modl.v1.ServerRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class OnlinePlayerCount {
    static final Duration CACHE_TIME = Duration.ofSeconds(5);

    private final ConnectedServersView servers;
    private final Clock clock;
    private final AtomicReference<Counted> latest = new AtomicReference<>();

    public OnlinePlayerCount(ConnectedServersView servers, Clock clock) {
        this.servers = servers;
        this.clock = clock;
    }

    public int total() {
        Instant now = clock.instant();
        Counted cached = latest.get();
        if (cached != null && now.isBefore(cached.countedAt().plus(CACHE_TIME))) {
            return cached.players();
        }
        Counted counted = new Counted(countCores(), now);
        latest.set(counted);
        return counted.players();
    }

    private int countCores() {
        long players = servers.connected().stream()
            .filter(server -> server.role() == ServerRole.SERVER_ROLE_CORE && server.hubConnected())
            .mapToLong(ConnectedServer::onlinePlayers)
            .sum();
        return (int) Math.min(Integer.MAX_VALUE, players);
    }

    private record Counted(int players, Instant countedAt) {
    }
}
