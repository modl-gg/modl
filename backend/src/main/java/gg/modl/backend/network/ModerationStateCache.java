package gg.modl.backend.network;

import gg.modl.proto.modl.v1.ModerationStateEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class ModerationStateCache {
    private final Clock clock;
    private final Map<String, Entry> bySource = new LinkedHashMap<>();

    public ModerationStateCache(Clock clock) {
        this.clock = clock;
    }

    synchronized void put(String coreConnectionId, CoreIdentity core, ModerationStateEvent stamped) {
        bySource.put(stamped.getSourceInstanceId(), new Entry(coreConnectionId, core, stamped, null));
    }

    synchronized List<ModerationStateEvent> current() {
        return bySource.values().stream().map(Entry::state).toList();
    }

    synchronized void coreClosed(String coreConnectionId) {
        Instant retireAt = clock.instant().plus(NetworkLimits.RETIRE_AFTER);
        bySource.replaceAll((source, entry) -> entry.coreConnectionId().equals(coreConnectionId) && entry.retireAt() == null
            ? entry.retiringAt(retireAt)
            : entry);
    }

    synchronized List<String> coreJoined(String sourceInstanceId, CoreIdentity core, String coreConnectionId) {
        Entry returning = bySource.get(sourceInstanceId);
        if (returning != null) {
            bySource.put(sourceInstanceId, new Entry(coreConnectionId, returning.core(), returning.state(), null));
        }
        return removeWhere(entry -> entry.retireAt() != null && entry.core().equals(core)
            && !entry.state().getSourceInstanceId().equals(sourceInstanceId));
    }

    synchronized List<String> retireDue() {
        Instant now = clock.instant();
        return removeWhere(entry -> entry.retireAt() != null && !entry.retireAt().isAfter(now));
    }

    private List<String> removeWhere(Predicate<Entry> retired) {
        List<String> removed = new ArrayList<>();
        for (Iterator<Map.Entry<String, Entry>> iterator = bySource.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<String, Entry> entry = iterator.next();
            if (retired.test(entry.getValue())) {
                iterator.remove();
                removed.add(entry.getKey());
            }
        }
        return removed;
    }

    private record Entry(String coreConnectionId, CoreIdentity core, ModerationStateEvent state, @Nullable Instant retireAt) {
        Entry retiringAt(Instant at) {
            return new Entry(coreConnectionId, core, state, at);
        }
    }
}
