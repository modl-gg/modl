package gg.modl.backend.update.check;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CheckScheduleTest {
    private static final Instant NOW = Instant.parse("2026-12-01T12:00:00Z");

    private final CheckSchedule schedule = new CheckSchedule(new Random());

    @RepeatedTest(50)
    void theFirstCheckWaitsTwoToTenMinutesAfterReady() {
        assertThat(schedule.firstCheck(NOW, null)).isBetween(NOW.plusSeconds(120), NOW.plusSeconds(600));
        assertThat(schedule.firstCheck(NOW, NOW.minusSeconds(5))).isBetween(NOW.plusSeconds(120), NOW.plusSeconds(600));
    }

    @RepeatedTest(50)
    void successChecksAgainInElevenToThirteenHours() {
        assertThat(schedule.afterSuccess(NOW)).isBetween(NOW.plus(Duration.ofHours(11)), NOW.plus(Duration.ofHours(13)));
    }

    @ParameterizedTest
    @CsvSource({"1, 1", "2, 2", "3, 4", "4, 8", "5, 16", "6, 24", "50, 24"})
    void failuresBackOff(int failures, int hours) {
        Duration base = Duration.ofHours(hours);

        assertThat(schedule.afterFailure(NOW, failures, null))
            .isBetween(NOW.plusMillis((long) (base.toMillis() * 0.9)), NOW.plusMillis((long) (base.toMillis() * 1.1) + 1));
    }

    @Test
    void aLongerRetryAfterWinsUpToTwentyFiveHours() {
        assertThat(schedule.afterFailure(NOW, 1, Duration.ofHours(5))).isEqualTo(NOW.plus(Duration.ofHours(5)));
        assertThat(schedule.afterFailure(NOW, 1, Duration.ofDays(9))).isEqualTo(NOW.plus(Duration.ofHours(25)));
    }

    @Test
    void aStoredTimeTooFarAheadIsClamped() {
        Instant kept = NOW.plus(Duration.ofHours(6));
        assertThat(schedule.firstCheck(NOW, kept)).isEqualTo(kept);
        assertThat(schedule.firstCheck(NOW, NOW.plus(Duration.ofDays(30)))).isBefore(NOW.plus(Duration.ofHours(14)));
    }
}
