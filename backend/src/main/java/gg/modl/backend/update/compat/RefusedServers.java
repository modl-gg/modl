package gg.modl.backend.update.compat;

import gg.modl.update.VersionCompatibility;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RefusedServers {
    static final int CAPACITY = 64;
    static final Duration EXPIRY = Duration.ofMinutes(10);

    private final Clock clock;
    private final Map<String, RefusedServer> refused = new LinkedHashMap<>();

    public synchronized void refused(PluginIdentity identity, VersionCompatibility compatibility) {
        if (identity.bootId() == null) {
            return;
        }
        Instant now = clock.instant();
        removeExpired(now);
        refused.remove(identity.bootId());
        if (refused.size() >= CAPACITY) {
            refused.remove(refused.keySet().iterator().next());
        }
        refused.put(identity.bootId(), new RefusedServer(identity.bootId(), identity.platform(), identity.pluginVersion(), compatibility,
            now));
    }

    public synchronized void accepted(@Nullable String bootId) {
        if (bootId != null && !refused.isEmpty()) {
            refused.remove(bootId);
        }
    }

    public synchronized List<RefusedServer> current() {
        removeExpired(clock.instant());
        return List.copyOf(refused.values());
    }

    private void removeExpired(Instant now) {
        Instant oldestAllowed = now.minus(EXPIRY);
        refused.values().removeIf(server -> server.refusedAt().isBefore(oldestAllowed));
    }

    public record RefusedServer(String bootId, String platform, String pluginVersion, VersionCompatibility compatibility,
                                Instant refusedAt) {
    }
}
