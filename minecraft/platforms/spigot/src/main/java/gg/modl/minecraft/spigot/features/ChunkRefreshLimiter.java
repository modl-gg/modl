package gg.modl.minecraft.spigot.features;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ChunkRefreshLimiter {
    private static final long WINDOW_NANOS = 50_000_000L;
    private static final long RECENT_MS = 30_000L;
    private static final int MAX_RECENT = 65_536;

    private final int perTick;
    private final Map<String, Long> recent = new ConcurrentHashMap<>();
    private long windowStart;
    private int usedInWindow;

    ChunkRefreshLimiter(int perTick) {
        this.perTick = perTick;
    }

    synchronized int allowance(int wanted) {
        long now = System.nanoTime();
        if (now - windowStart >= WINDOW_NANOS) {
            windowStart = now;
            usedInWindow = 0;
        }
        int granted = Math.max(0, Math.min(wanted, perTick - usedInWindow));
        usedInWindow += granted;
        return granted;
    }

    boolean firstRecently(String world, int chunkX, int chunkZ, long nowMillis) {
        if (recent.size() > MAX_RECENT) recent.entrySet().removeIf(entry -> nowMillis - entry.getValue() > RECENT_MS);
        String key = world + ':' + chunkX + ':' + chunkZ;
        Long last = recent.get(key);
        if (last != null && nowMillis - last < RECENT_MS) return false;
        recent.put(key, nowMillis);
        return true;
    }
}
