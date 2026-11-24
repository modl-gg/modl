package gg.modl.backend.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ValidityPeriodTest {

    @Test
    void wholeHoursAndMinutesAreNamedExactly() {
        assertThat(ValidityPeriod.ofSeconds(86_400).describe()).isEqualTo("24 hours");
        assertThat(ValidityPeriod.ofSeconds(3_600).describe()).isEqualTo("1 hour");
        assertThat(ValidityPeriod.ofSeconds(300).describe()).isEqualTo("5 minutes");
    }

    @Test
    void partialMinutesAreRoundedDownSoValidityIsNeverOverstated() {
        assertThat(ValidityPeriod.ofSeconds(90).describe()).isEqualTo("1 minute");
        assertThat(ValidityPeriod.ofSeconds(5_400).describe()).isEqualTo("90 minutes");
    }

    @Test
    void periodsShorterThanAMinuteAreShownInSeconds() {
        assertThat(ValidityPeriod.ofSeconds(45).describe()).isEqualTo("45 seconds");
        assertThat(ValidityPeriod.ofSeconds(1).describe()).isEqualTo("1 second");
    }
}
