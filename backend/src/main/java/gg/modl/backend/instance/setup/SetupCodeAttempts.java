package gg.modl.backend.instance.setup;

import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
class SetupCodeAttempts {
    static final int MAX_FAILURES_PER_CLIENT = 5;
    private static final int MAX_TRACKED_CLIENTS = 4096;

    private final Map<String, Integer> attemptsByClient = new HashMap<>();

    synchronized boolean tryAcquire(SetupClient client) {
        String key = client.key();
        int used = attemptsByClient.getOrDefault(key, 0);
        if (used >= MAX_FAILURES_PER_CLIENT) {
            return false;
        }
        if (used > 0 || attemptsByClient.size() < MAX_TRACKED_CLIENTS) {
            attemptsByClient.put(key, used + 1);
        }
        return true;
    }

    synchronized void refund(SetupClient client) {
        attemptsByClient.computeIfPresent(client.key(), (ignored, used) -> used > 1 ? used - 1 : null);
    }

    synchronized void reset() {
        attemptsByClient.clear();
    }
}
