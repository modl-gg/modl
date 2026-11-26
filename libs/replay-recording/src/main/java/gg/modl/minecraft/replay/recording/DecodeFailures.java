package gg.modl.minecraft.replay.recording;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

final class DecodeFailures {
    private final Logger logger;
    private final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();

    DecodeFailures(Logger logger) {
        this.logger = logger;
    }

    void record(String kind, Throwable failure) {
        AtomicLong count = counts.computeIfAbsent(kind, ignored -> new AtomicLong());
        if (count.incrementAndGet() == 1 && logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, "[replay] Skipping " + kind + " packets that cannot be decoded: " + failure);
        }
    }

    void summarize() {
        if (counts.isEmpty() || !logger.isLoggable(Level.FINE)) return;
        StringBuilder summary = new StringBuilder("[replay] Undecodable packets skipped:");
        for (Map.Entry<String, AtomicLong> entry : counts.entrySet()) {
            summary.append(' ').append(entry.getKey()).append('=').append(entry.getValue().get());
        }
        logger.fine(summary.toString());
    }
}
