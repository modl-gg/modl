package gg.modl.backend.auth.signin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.infrastructure.exception.TooManyRequestsException;
import gg.modl.backend.infrastructure.ratelimit.BucketPool;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CredentialAttemptsTest {
    private static final Optional<String> NETWORK = Optional.of("203.0.113.7");

    private final CredentialAttempts attempts = new CredentialAttempts(new BucketPool());

    @Test
    void fiveFailuresFromOneNetworkLockThatPairWithARetryAfter() {
        for (int attempt = 0; attempt < 5; attempt++) {
            attempts.reservePassword("mod@example.com", NETWORK);
        }

        assertThatThrownBy(() -> attempts.reservePassword("Mod@Example.com", NETWORK))
            .isInstanceOfSatisfying(TooManyRequestsException.class, refused -> {
                assertThat(refused.getMachineCode()).isEqualTo("TOO_MANY_ATTEMPTS");
                assertThat(refused.retryAfter()).isPresent();
            });
        attempts.reservePassword("mod@example.com", Optional.of("198.51.100.9"));
    }

    @Test
    void anUnidentifiedClientSkipsThePairBucketButStillSpendsTheIdentifierBudget() {
        for (int attempt = 0; attempt < 50; attempt++) {
            attempts.reservePassword("shared@example.com", Optional.empty());
        }

        assertThatThrownBy(() -> attempts.reservePassword("shared@example.com", Optional.empty()))
            .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void refundedAndSuccessfulAttemptsGiveTheirTokensBack() {
        for (int attempt = 0; attempt < 20; attempt++) {
            attempts.reservePassword("refund@example.com", NETWORK).refund();
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            attempts.reservePassword("success@example.com", NETWORK);
        }
        attempts.reservePassword("success@example.com", NETWORK).succeeded();

        for (int attempt = 0; attempt < 5; attempt++) {
            attempts.reservePassword("success@example.com", NETWORK);
        }
    }

    @Test
    void secondFactorCodesHaveTheirOwnShortWindow() {
        for (int attempt = 0; attempt < 5; attempt++) {
            attempts.reserveSecondFactor("mod@example.com");
        }

        assertThatThrownBy(() -> attempts.reserveSecondFactor("mod@example.com")).isInstanceOf(TooManyRequestsException.class);
        attempts.reservePassword("mod@example.com", NETWORK);
    }

    @Test
    void concurrentReservationsNeverAdmitMoreThanTheCapacity() throws InterruptedException {
        ExecutorService callers = Executors.newFixedThreadPool(8);
        AtomicInteger admitted = new AtomicInteger();
        for (int call = 0; call < 40; call++) {
            callers.execute(() -> {
                try {
                    attempts.reserveSecondFactor("race@example.com");
                    admitted.incrementAndGet();
                } catch (TooManyRequestsException refused) {
                    return;
                }
            });
        }
        callers.shutdown();
        callers.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(admitted.get()).isEqualTo(5);
    }
}
