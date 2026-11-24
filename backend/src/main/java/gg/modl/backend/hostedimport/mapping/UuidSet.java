package gg.modl.backend.hostedimport.mapping;

import java.util.UUID;

final class UuidSet {
    private static final int INITIAL_CAPACITY = 1 << 12;

    private long[] most = new long[INITIAL_CAPACITY];
    private long[] least = new long[INITIAL_CAPACITY];
    private boolean[] used = new boolean[INITIAL_CAPACITY];
    private int size;

    boolean add(UUID uuid) {
        if ((size + 1) * 2L > used.length) {
            grow();
        }
        long high = uuid.getMostSignificantBits();
        long low = uuid.getLeastSignificantBits();
        int slot = slotOf(high, low, most, least, used);
        if (used[slot]) {
            return false;
        }
        store(slot, high, low);
        size++;
        return true;
    }

    private void store(int slot, long high, long low) {
        used[slot] = true;
        most[slot] = high;
        least[slot] = low;
    }

    private void grow() {
        long[] oldMost = most;
        long[] oldLeast = least;
        boolean[] oldUsed = used;
        int capacity = oldUsed.length * 2;
        most = new long[capacity];
        least = new long[capacity];
        used = new boolean[capacity];
        for (int i = 0; i < oldUsed.length; i++) {
            if (oldUsed[i]) {
                store(slotOf(oldMost[i], oldLeast[i], most, least, used), oldMost[i], oldLeast[i]);
            }
        }
    }

    private static int slotOf(long high, long low, long[] highs, long[] lows, boolean[] occupied) {
        int mask = occupied.length - 1;
        long mixed = (high ^ low) * 0x9E3779B97F4A7C15L;
        int slot = (int) (mixed ^ (mixed >>> 32)) & mask;
        while (occupied[slot] && (highs[slot] != high || lows[slot] != low)) {
            slot = (slot + 1) & mask;
        }
        return slot;
    }
}
