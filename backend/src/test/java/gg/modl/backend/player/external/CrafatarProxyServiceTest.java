package gg.modl.backend.player.external;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CrafatarProxyServiceTest {
    private static final String UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
    private static final byte[] PNG = {1, 2, 3};

    @Test
    void slowUpstreamsHoldAtMostABoundedNumberOfRequestsAndTheRestAnswerAtOnce() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch allStarted = new CountDownLatch(CrafatarProxyService.MAX_CONCURRENT_FETCHES);
        CrafatarProxyService service = new CrafatarProxyService((uuid, size, overlay) -> {
            allStarted.countDown();
            await(release);
            return PNG;
        });
        try (ExecutorService callers = Executors.newFixedThreadPool(CrafatarProxyService.MAX_CONCURRENT_FETCHES)) {
            List<Future<byte[]>> held = new ArrayList<>();
            for (int caller = 0; caller < CrafatarProxyService.MAX_CONCURRENT_FETCHES; caller++) {
                int size = 16 + caller;
                held.add(callers.submit(() -> service.getAvatar(UUID, size, true)));
            }
            assertThat(allStarted.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(service.getAvatar(UUID, 64, true)).isNull();

            release.countDown();
            for (Future<byte[]> call : held) {
                assertThat(call.get(10, TimeUnit.SECONDS)).isEqualTo(PNG);
            }
        }
    }

    @Test
    void anUnknownAvatarIsAskedForOnceAndAKnownOneIsServedFromTheCache() {
        AtomicInteger missingCalls = new AtomicInteger();
        AtomicInteger foundCalls = new AtomicInteger();
        CrafatarProxyService service = new CrafatarProxyService((uuid, size, overlay) -> {
            if (size == 32) {
                missingCalls.incrementAndGet();
                return null;
            }
            foundCalls.incrementAndGet();
            return PNG;
        });

        assertThat(service.getAvatar(UUID, 32, true)).isNull();
        assertThat(service.getAvatar(UUID, 32, true)).isNull();
        assertThat(service.getAvatar(UUID, 64, true)).isEqualTo(PNG);
        assertThat(service.getAvatar(UUID, 64, true)).isEqualTo(PNG);

        assertThat(missingCalls).hasValue(1);
        assertThat(foundCalls).hasValue(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
