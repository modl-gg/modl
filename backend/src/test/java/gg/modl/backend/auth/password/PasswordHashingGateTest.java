package gg.modl.backend.auth.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.infrastructure.exception.ServiceUnavailableException;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PasswordHashingGateTest {
    private final PasswordHashingGate gate = new PasswordHashingGate(1);

    @TempDir
    Path dataDirectory;
    private final ExecutorService callers = Executors.newFixedThreadPool(PasswordHashingGate.MAX_WAITERS + 4);

    @AfterEach
    void stop() throws InterruptedException {
        callers.shutdownNow();
        gate.destroy();
    }

    @Test
    void neverRunsMoreHashesThanItHasPermits() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger highest = new AtomicInteger();
        Runnable call = () -> gate.run(() -> {
            highest.accumulateAndGet(running.incrementAndGet(), Math::max);
            sleep(20);
            running.decrementAndGet();
            return null;
        });
        Future<?> first = callers.submit(call);
        Future<?> second = callers.submit(call);
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);

        assertThat(highest.get()).isEqualTo(1);
    }

    @Test
    void aCallerThatWaitsTooLongIsTurnedAwayAsBusy() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        callers.execute(() -> gate.run(() -> await(release)));
        sleep(100);

        assertThatThrownBy(() -> gate.run(() -> "late"))
            .isInstanceOfSatisfying(ServiceUnavailableException.class,
                busy -> assertThat(busy.getMachineCode()).isEqualTo("PASSWORD_HASHING_BUSY"));
        release.countDown();
    }

    @SqlContractTest
    void hashingInsideAUnitOfWorkIsRefused(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            assertThatThrownBy(() -> database.unitOfWork().write(() -> gate.run(() -> "inside")))
                .isInstanceOf(IllegalStateException.class);
            assertThat(gate.run(() -> "outside")).isEqualTo("outside");
        }
    }

    @Test
    void shutdownLeavesNoWorkerThreads() throws Exception {
        Thread worker = gate.run(Thread::currentThread);

        gate.destroy();
        worker.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(worker.getName()).startsWith("modl-password-hash-");
        assertThat(worker.isAlive()).isFalse();
    }

    @Test
    void largeHostsGetTwoPermitsAndSmallHostsOne() {
        assertThat(PasswordHashingGate.permitsFor(Runtime.getRuntime())).isBetween(1, 2);
    }

    private static Object await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
