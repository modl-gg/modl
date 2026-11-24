package gg.modl.backend.auth.totp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class TotpTest {
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @ParameterizedTest
    @CsvSource({
        "59, 287082",
        "1111111109, 081804",
        "1234567890, 005924",
        "2000000000, 279037",
        "20000000000, 353130"
    })
    void rfc6238Sha1VectorsMatchOnTheirLastSixDigits(long epochSecond, String code) {
        assertThat(Totp.codeAt(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(epochSecond)))).isEqualTo(code);
    }

    @Test
    void oneStepEitherSideIsAcceptedAndNothingFurther() {
        Instant now = Instant.ofEpochSecond(1_000_000_020L);
        long current = Totp.stepAt(now);

        assertThat(Totp.matchingStep(RFC_SECRET, Totp.codeAt(RFC_SECRET, current - 1), now)).hasValue(current - 1);
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.codeAt(RFC_SECRET, current + 1), now)).hasValue(current + 1);
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.codeAt(RFC_SECRET, current - 2), now)).isEmpty();
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.codeAt(RFC_SECRET, current + 2), now)).isEmpty();
    }

    @Test
    void theHighestMatchingStepWinsSoAReplayGuardCannotBeSteppedBackwards() {
        Instant now = Instant.ofEpochSecond(59);
        String code = Totp.codeAt(RFC_SECRET, Totp.stepAt(now));

        assertThat(Totp.matchingStep(RFC_SECRET, code, now)).hasValue(Totp.stepAt(now));
        assertThat(Totp.matchingStep(RFC_SECRET, "abcdef", now)).isEmpty();
    }
}
