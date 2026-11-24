package gg.modl.backend.embedded;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class ServerResources {
    private static final Duration THREAD_EXIT_BUDGET = Duration.ofSeconds(2);

    private final ClassLoader serverClassLoader;
    private final JulForwarding julForwarding;

    ServerResources(ClassLoader serverClassLoader, JulForwarding julForwarding) {
        this.serverClassLoader = serverClassLoader;
        this.julForwarding = julForwarding;
    }

    void release() {
        try {
            List<Thread> survivors = awaitServerThreads(serverClassLoader, THREAD_EXIT_BUDGET, Set.of(HostLogAppender.SUMMARY_THREAD));
            if (!survivors.isEmpty()) {
                log.warn("Threads still running after the modl server stopped: {}", survivors.stream().map(Thread::getName).toList());
            }
        } finally {
            julForwarding.uninstall();
            EmbeddedLogging.shutdown();
        }
    }

    static List<Thread> awaitServerThreads(ClassLoader classLoader, Duration budget, Set<String> exemptNames) {
        long deadline = System.nanoTime() + budget.toNanos();
        List<Thread> pending = serverThreads(classLoader, exemptNames);
        for (Thread thread : pending) {
            long remainingMillis = Math.max(0, (deadline - System.nanoTime()) / 1_000_000);
            if (remainingMillis == 0) {
                break;
            }
            try {
                thread.join(remainingMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return pending.stream().filter(Thread::isAlive).toList();
    }

    private static List<Thread> serverThreads(ClassLoader classLoader, Set<String> exemptNames) {
        Thread current = Thread.currentThread();
        return Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread != current)
            .filter(Thread::isAlive)
            .filter(thread -> thread.getContextClassLoader() == classLoader)
            .filter(thread -> !exemptNames.contains(thread.getName()))
            .toList();
    }
}
