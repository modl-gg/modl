package gg.modl.backend.iplookup.service;

import gg.modl.backend.iplookup.model.IpLookupProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class IpLookupBackoff {
    static final Duration AFTER_REFUSAL = Duration.ofHours(1);
    static final Duration AFTER_FAILURE = Duration.ofMinutes(5);

    private final Clock clock;
    private final Map<IpLookupProvider, Pause> pauses = new ConcurrentHashMap<>();

    public boolean allows(IpLookupProvider provider) {
        Pause pause = pauses.get(provider);
        return pause == null || !pause.activeAt(clock.instant());
    }

    public boolean pauseAfterRefusal(IpLookupProvider provider) {
        Instant now = clock.instant();
        return pause(provider, new Pause(PauseKind.REFUSAL, now.plus(AFTER_REFUSAL)), now);
    }

    public void pauseAfterFailure(IpLookupProvider provider) {
        Instant now = clock.instant();
        pause(provider, new Pause(PauseKind.FAILURE, now.plus(AFTER_FAILURE)), now);
    }

    public boolean pauseUntilNextDay(IpLookupProvider provider) {
        Instant now = clock.instant();
        Instant nextDay = now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return pause(provider, new Pause(PauseKind.DAILY_LIMIT, nextDay), now);
    }

    public void clear(IpLookupProvider provider) {
        pauses.remove(provider);
    }

    private boolean pause(IpLookupProvider provider, Pause next, Instant now) {
        AtomicBoolean announce = new AtomicBoolean();
        pauses.compute(provider, (key, current) -> {
            announce.set(current == null || !current.activeAt(now) || current.kind() != next.kind());
            return next;
        });
        return announce.get();
    }

    private enum PauseKind {
        FAILURE,
        REFUSAL,
        DAILY_LIMIT
    }

    private record Pause(PauseKind kind, Instant until) {
        boolean activeAt(Instant now) {
            return now.isBefore(until);
        }
    }
}
