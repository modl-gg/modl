package gg.modl.backend.instance;

import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

public final class ParallelCalls {
    private ParallelCalls() {
    }

    public static <T> List<Outcome<T>> run(int callers, IntFunction<T> call) throws Exception {
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(callers)) {
            List<Future<Outcome<T>>> running = new ArrayList<>();
            for (int caller = 0; caller < callers; caller++) {
                int index = caller;
                running.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return new Outcome<>(call.apply(index), null);
                    } catch (RuntimeException failure) {
                        return new Outcome<T>(null, failure);
                    }
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<Outcome<T>> future : running) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        }
    }

    public record Outcome<T>(@Nullable T value, @Nullable RuntimeException failure) {
        public boolean succeeded() {
            return failure == null;
        }
    }
}
