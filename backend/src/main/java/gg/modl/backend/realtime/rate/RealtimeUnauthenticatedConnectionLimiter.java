package gg.modl.backend.realtime.rate;

import gg.modl.backend.infrastructure.web.ClientNetworks;
import gg.modl.backend.realtime.config.RealtimeProperties;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RealtimeUnauthenticatedConnectionLimiter {
    private final RealtimeProperties properties;
    private final Object lock = new Object();
    private final Map<String, Integer> perIpCounts = new HashMap<>();
    private int totalCount;

    public Admission tryAcquire(@Nullable String clientIp) {
        String ip = normalize(clientIp);
        synchronized (lock) {
            int current = perIpCounts.getOrDefault(ip, 0);
            if (current >= properties.getMaxUnauthenticatedConnectionsPerIp()) {
                return Admission.REJECTED_PER_IP;
            }
            if (totalCount >= properties.getMaxUnauthenticatedConnections()) {
                return Admission.REJECTED_GLOBAL;
            }
            totalCount++;
            perIpCounts.put(ip, current + 1);
            return Admission.ADMITTED;
        }
    }

    public void release(@Nullable String clientIp) {
        String ip = normalize(clientIp);
        synchronized (lock) {
            if (totalCount > 0) {
                totalCount--;
            }
            Integer current = perIpCounts.get(ip);
            if (current != null) {
                if (current <= 1) {
                    perIpCounts.remove(ip);
                } else {
                    perIpCounts.put(ip, current - 1);
                }
            }
        }
    }

    private static String normalize(@Nullable String clientIp) {
        return ClientNetworks.keyOf(clientIp);
    }

    public enum Admission {
        ADMITTED,
        REJECTED_GLOBAL,
        REJECTED_PER_IP
    }
}
