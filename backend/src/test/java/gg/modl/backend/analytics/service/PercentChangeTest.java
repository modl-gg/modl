package gg.modl.backend.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PercentChangeTest {
    @Test
    void theChangeIsRoundedRelativeToThePreviousCount() {
        assertThat(PercentChange.between(1, 3)).isEqualTo(200);
        assertThat(PercentChange.between(12, 15)).isEqualTo(25);
        assertThat(PercentChange.between(8, 4)).isEqualTo(-50);
    }

    @Test
    void withoutAPreviousCountThereIsNoChange() {
        assertThat(PercentChange.between(0, 15)).isZero();
    }
}
