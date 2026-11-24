package gg.modl.backend.auth.link;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.infrastructure.exception.TooManyRequestsException;
import gg.modl.backend.infrastructure.ratelimit.FrozenTimeBuckets;
import org.junit.jupiter.api.Test;

import java.util.Locale;

class PlayerLinkThrottleTest {
    private static final String PLAYER = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    @Test
    void onePlayerRepeatingLinkRequestsIsThrottledWithoutTouchingOtherPlayers() {
        PlayerLinkThrottle throttle = new PlayerLinkThrottle(FrozenTimeBuckets.pool());
        for (int request = 0; request < 5; request++) {
            throttle.requireAdmission(PLAYER.toUpperCase(Locale.ROOT));
        }

        assertThatThrownBy(() -> throttle.requireAdmission(PLAYER)).isInstanceOf(TooManyRequestsException.class);
        throttle.requireAdmission("11111111-2222-3333-4444-555555555555");
    }
}
