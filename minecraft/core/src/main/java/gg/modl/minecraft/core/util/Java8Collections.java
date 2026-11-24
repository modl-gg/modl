package gg.modl.minecraft.core.util;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class Java8Collections {

    private Java8Collections() {}

    public static <T> List<T> listOf() {
        return Collections.emptyList();
    }

    public static <T> List<T> listOf(T e1) {
        return Collections.singletonList(e1);
    }

    @SafeVarargs
    public static <T> List<T> listOf(T... elements) {
        List<T> copy = new ArrayList<>(elements.length);
        for (T element : elements) copy.add(element);
        return Collections.unmodifiableList(copy);
    }

    public static <T> Set<T> setOf() {
        return Collections.emptySet();
    }

    public static <T> Set<T> setOf(T e1) {
        return Collections.singleton(e1);
    }

    @SafeVarargs
    public static <T> Set<T> setOf(T... elements) {
        Set<T> copy = new LinkedHashSet<>();
        for (T element : elements) copy.add(element);
        return Collections.unmodifiableSet(copy);
    }

    public static <K, V> Map<K, V> mapOf() {
        return Collections.emptyMap();
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1) {
        return Collections.singletonMap(k1, v1);
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2) {
        Map<K, V> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return Collections.unmodifiableMap(map);
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3) {
        Map<K, V> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);
        return Collections.unmodifiableMap(map);
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4) {
        Map<K, V> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);
        map.put(k4, v4);
        return Collections.unmodifiableMap(map);
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5) {
        Map<K, V> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);
        map.put(k4, v4);
        map.put(k5, v5);
        return Collections.unmodifiableMap(map);
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5,
                                          K k6, V v6) {
        Map<K, V> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);
        map.put(k4, v4);
        map.put(k5, v5);
        map.put(k6, v6);
        return Collections.unmodifiableMap(map);
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5,
                                          K k6, V v6, K k7, V v7) {
        Map<K, V> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);
        map.put(k4, v4);
        map.put(k5, v5);
        map.put(k6, v6);
        map.put(k7, v7);
        return Collections.unmodifiableMap(map);
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5,
                                          K k6, V v6, K k7, V v7, K k8, V v8) {
        Map<K, V> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);
        map.put(k4, v4);
        map.put(k5, v5);
        map.put(k6, v6);
        map.put(k7, v7);
        map.put(k8, v8);
        return Collections.unmodifiableMap(map);
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5,
                                          K k6, V v6, K k7, V v7, K k8, V v8, K k9, V v9) {
        Map<K, V> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);
        map.put(k4, v4);
        map.put(k5, v5);
        map.put(k6, v6);
        map.put(k7, v7);
        map.put(k8, v8);
        map.put(k9, v9);
        return Collections.unmodifiableMap(map);
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5,
                                          K k6, V v6, K k7, V v7, K k8, V v8, K k9, V v9, K k10, V v10) {
        Map<K, V> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);
        map.put(k4, v4);
        map.put(k5, v5);
        map.put(k6, v6);
        map.put(k7, v7);
        map.put(k8, v8);
        map.put(k9, v9);
        map.put(k10, v10);
        return Collections.unmodifiableMap(map);
    }

    public static <K, V> Map.Entry<K, V> entry(K key, V value) {
        return new AbstractMap.SimpleImmutableEntry<>(key, value);
    }

    @SafeVarargs
    public static <K, V> Map<K, V> mapOfEntries(Map.Entry<K, V>... entries) {
        Map<K, V> map = new LinkedHashMap<>();
        for (Map.Entry<K, V> e : entries) {
            map.put(e.getKey(), e.getValue());
        }
        return Collections.unmodifiableMap(map);
    }

    public static <T> CompletableFuture<T> failedFuture(Throwable ex) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(ex);
        return future;
    }

    private static final SharedExecutor<ScheduledExecutorService> TIMEOUT_SCHEDULER =
            new SharedExecutor<>(Java8Collections::newTimeoutScheduler);

    private static ScheduledExecutorService newTimeoutScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "cf-timeout");
            t.setDaemon(true);
            return t;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    public static <T> CompletableFuture<T> orTimeout(CompletableFuture<T> future, long timeout, TimeUnit unit) {
        return orTimeout(future, timeout, unit, TIMEOUT_SCHEDULER.get());
    }

    public static CompletableFuture<Void> delay(long delay, TimeUnit unit) {
        CompletableFuture<Void> elapsed = new CompletableFuture<>();
        ScheduledFuture<?> tick = TIMEOUT_SCHEDULER.get().schedule(() -> elapsed.complete(null), delay, unit);
        elapsed.exceptionally(cancelled -> {
            tick.cancel(false);
            return null;
        });
        return elapsed;
    }

    public static void shutdown() {
        TIMEOUT_SCHEDULER.shutdown();
    }

    static <T> CompletableFuture<T> orTimeout(
            CompletableFuture<T> future,
            long timeout,
            TimeUnit unit,
            ScheduledExecutorService scheduler
    ) {
        ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
            if (!future.isDone()) {
                future.completeExceptionally(new TimeoutException());
            }
        }, timeout, unit);
        future.thenRun(() -> timeoutTask.cancel(false)).exceptionally(failure -> {
            timeoutTask.cancel(false);
            return null;
        });
        return future;
    }
}
