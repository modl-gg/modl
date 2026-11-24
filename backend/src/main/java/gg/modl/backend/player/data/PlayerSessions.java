package gg.modl.backend.player.data;

import java.time.Duration;

public final class PlayerSessions {
    private static final Duration LONGEST = Duration.ofDays(366);

    private PlayerSessions() {
    }

    public static long countedSeconds(Duration session) {
        if (session.isNegative()) {
            return 0L;
        }
        return (session.compareTo(LONGEST) > 0 ? LONGEST : session).toSeconds();
    }
}
