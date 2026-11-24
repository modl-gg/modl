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
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class PendingMemberActions {
    private final Clock clock;
    private final Map<String, Pending> byRequestId = new LinkedHashMap<>();
    private final Map<String, Integer> countByOwner = new HashMap<>();

    public PendingMemberActions(Clock clock) {
        this.clock = clock;
    }

    synchronized boolean track(String requestId, String memberConnectionId, String coreConnectionId, @Nullable String actorUuid) {
        if (byRequestId.containsKey(requestId)) {
            return false;
        }
        if (countByOwner.getOrDefault(memberConnectionId, 0) >= NetworkLimits.PENDING_ACTIONS_PER_MEMBER) {
            return false;
        }
        byRequestId.put(requestId, new Pending(requestId, memberConnectionId, coreConnectionId, actorUuid,
            clock.instant().plus(NetworkLimits.ACTION_RESULT_TIMEOUT)));
        countByOwner.merge(memberConnectionId, 1, Integer::sum);
        return true;
    }

    synchronized Optional<Pending> complete(String requestId, String coreConnectionId) {
        Pending pending = byRequestId.get(requestId);
        if (pending == null || !pending.coreConnectionId().equals(coreConnectionId)) {
            return Optional.empty();
        }
        byRequestId.remove(requestId);
        release(pending);
        return Optional.of(pending);
    }

    synchronized List<Pending> coreClosed(String coreConnectionId) {
        return removeWhere(pending -> pending.coreConnectionId().equals(coreConnectionId));
    }

    synchronized void memberClosed(String memberConnectionId) {
        removeWhere(pending -> pending.memberConnectionId().equals(memberConnectionId));
    }

    synchronized void expire() {
        Instant now = clock.instant();
        removeWhere(pending -> !pending.expiresAt().isAfter(now));
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
        countByOwner.computeIfPresent(pending.memberConnectionId(), (owner, count) -> count > 1 ? count - 1 : null);
    }

    record Pending(String requestId, String memberConnectionId, String coreConnectionId, @Nullable String actorUuid,
                   Instant expiresAt) {
    }
}
