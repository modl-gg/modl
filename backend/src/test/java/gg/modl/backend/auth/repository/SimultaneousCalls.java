package gg.modl.backend.auth.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

final class SimultaneousCalls {
    static final int CALLERS = 8;
    private static final long TIMEOUT_SECONDS = 30;

    private SimultaneousCalls() {
    }

    static <T> List<T> run(int callers, IntFunction<T> call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService threads = Executors.newFixedThreadPool(callers)) {
            List<Future<T>> pending = new ArrayList<>();
            for (int caller = 0; caller < callers; caller++) {
                int index = caller;
                pending.add(threads.submit(() -> {
                    start.await();
                    return call.apply(index);
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> result : pending) {
                results.add(result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        }
    }
}
