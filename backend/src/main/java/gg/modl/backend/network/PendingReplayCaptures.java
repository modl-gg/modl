package gg.modl.backend.network;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

@Component
public class PendingReplayCaptures {
    private final Clock clock;
    private final Map<String, Pending> byRequestId = new LinkedHashMap<>();
    private final Map<String, Integer> countByOwner = new HashMap<>();

    public PendingReplayCaptures(Clock clock) {
        this.clock = clock;
    }

    synchronized boolean track(String requestId, String coreConnectionId, String memberConnectionId) {
        if (byRequestId.containsKey(requestId)) {
            return false;
        }
        if (countByOwner.getOrDefault(coreConnectionId, 0) >= NetworkLimits.PENDING_CAPTURES_PER_CORE) {
            return false;
        }
        byRequestId.put(requestId, new Pending(requestId, coreConnectionId, memberConnectionId,
            clock.instant().plus(NetworkLimits.CAPTURE_DEADLINE)));
        countByOwner.merge(coreConnectionId, 1, Integer::sum);
        return true;
    }

    synchronized Optional<Pending> complete(String requestId, String memberConnectionId) {
        Pending pending = byRequestId.get(requestId);
        if (pending == null || !pending.memberConnectionId().equals(memberConnectionId)) {
            return Optional.empty();
        }
        byRequestId.remove(requestId);
        release(pending);
        return Optional.of(pending);
    }

    synchronized List<Pending> memberClosed(String memberConnectionId) {
        return removeWhere(pending -> pending.memberConnectionId().equals(memberConnectionId));
    }

    synchronized void coreClosed(String coreConnectionId) {
        removeWhere(pending -> pending.coreConnectionId().equals(coreConnectionId));
    }

    synchronized List<Pending> expire() {
        Instant now = clock.instant();
        return removeWhere(pending -> !pending.deadline().isAfter(now));
    }

    private List<Pending> removeWhere(Predicate<Pending> condition) {
        List<Pending> removed = new ArrayList<>();
        for (Iterator<Pending> iterator = byRequestId.values().iterator(); iterator.hasNext(); ) {
            Pending pending = iterator.next();
            if (condition.test(pending)) {
                iterator.remove();
                release(pending);
                removed.add(pending);
            }
        }
        return removed;
    }

    private void release(Pending pending) {
        countByOwner.computeIfPresent(pending.coreConnectionId(), (owner, count) -> count > 1 ? count - 1 : null);
    }

    record Pending(String requestId, String coreConnectionId, String memberConnectionId, Instant deadline) {
    }
}
