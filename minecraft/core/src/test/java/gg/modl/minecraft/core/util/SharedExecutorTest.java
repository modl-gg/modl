package gg.modl.minecraft.core.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedExecutorTest {
    private final SharedExecutor<ExecutorService> shared = new SharedExecutor<>(Executors::newSingleThreadExecutor);

    @Test
    void reusesOneExecutorUntilItIsShutDown() {
        ExecutorService first = shared.get();

        assertSame(first, shared.get());
        shared.shutdown();
        assertTrue(first.isShutdown());
    }

    @Test
    void startsAFreshExecutorAfterAShutdown() {
        ExecutorService first = shared.get();
        shared.shutdownNow();

        ExecutorService second = shared.get();

        assertNotSame(first, second);
        assertFalse(second.isShutdown());
        shared.shutdownNow();
    }
}
