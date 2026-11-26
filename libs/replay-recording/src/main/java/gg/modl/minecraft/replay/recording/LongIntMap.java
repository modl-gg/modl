package gg.modl.minecraft.replay.recording;

import java.util.Arrays;

final class LongIntMap {
    private static final long EMPTY = Long.MIN_VALUE;

    private long[] keys;
    private int[] values;
    private int size;

    LongIntMap() {
        keys = new long[16];
        values = new int[16];
        Arrays.fill(keys, EMPTY);
    }

    int size() {
        return size;
    }

    void put(long key, int value) {
        if ((size + 1) * 2 > keys.length) grow();
        int slot = slotOf(keys, key);
        if (keys[slot] == EMPTY) {
            keys[slot] = key;
            size++;
        }
        values[slot] = value;
    }

    void forEach(Consumer consumer) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != EMPTY) consumer.accept(keys[i], values[i]);
        }
    }

    LongIntMap copy() {
        LongIntMap copy = new LongIntMap();
        copy.keys = keys.clone();
        copy.values = values.clone();
        copy.size = size;
        return copy;
    }

    long estimatedBytes() {
        return keys.length * 12L;
    }

    private void grow() {
        long[] oldKeys = keys;
        int[] oldValues = values;
        keys = new long[oldKeys.length * 2];
        values = new int[oldKeys.length * 2];
        Arrays.fill(keys, EMPTY);
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] == EMPTY) continue;
            int slot = slotOf(keys, oldKeys[i]);
            keys[slot] = oldKeys[i];
            values[slot] = oldValues[i];
        }
    }

    private static int slotOf(long[] table, long key) {
        int mask = table.length - 1;
        int slot = (int) (mix(key) & mask);
        while (table[slot] != EMPTY && table[slot] != key) slot = (slot + 1) & mask;
        return slot;
    }

    private static long mix(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return h ^ (h >>> 32);
    }

    interface Consumer {
        void accept(long key, int value);
    }
}
