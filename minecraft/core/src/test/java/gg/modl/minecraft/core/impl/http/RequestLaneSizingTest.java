package gg.modl.minecraft.core.impl.http;

import gg.modl.minecraft.core.login.LoginBudget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestLaneSizingTest {
    private static final int LOGINS_PER_THREAD_PER_SECOND_AT_300_MS = 3;
    private static final long LOGIN_TIMEOUT_SECONDS = LoginBudget.SECONDS;

    @Test
    void theLoginLaneQueuesMoreThanItCanServeBeforeALoginTimesOut() {
        long servedBeforeTimeout = AbstractModlHttpTransport.LOGIN_LANE_THREADS * LOGINS_PER_THREAD_PER_SECOND_AT_300_MS
                * LOGIN_TIMEOUT_SECONDS;

        assertEquals(16, AbstractModlHttpTransport.LOGIN_LANE_THREADS);
        assertTrue(AbstractModlHttpTransport.LOGIN_LANE_QUEUE >= servedBeforeTimeout);
    }

    @Test
    void theBackgroundLaneHoldsARestartOfAThousandPlayersSeveralTimesOver() {
        assertEquals(8, AbstractModlHttpTransport.BACKGROUND_LANE_THREADS);
        assertTrue(AbstractModlHttpTransport.BACKGROUND_LANE_QUEUE >= 4 * 1000);
    }
}
