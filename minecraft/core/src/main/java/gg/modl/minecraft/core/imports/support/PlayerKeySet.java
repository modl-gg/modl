package gg.modl.minecraft.core.imports.support;

import gg.modl.minecraft.core.imports.source.SourceException;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.UUID;

public final class PlayerKeySet implements Iterable<String> {
    private static final int INITIAL_PAIRS = 1024;
    private static final int GROWTH_LIMIT_FACTOR = 2;

    private final int limit;
    private long[] pairs = new long[INITIAL_PAIRS * 2];
    private int size;
    private int compacted;
    private boolean overflowed;

    public PlayerKeySet() {
        this(PlayerLimit.MAX_PLAYERS);
    }

    PlayerKeySet(int limit) {
        this.limit = limit;
    }

    public void add(String uuid) {
        UUID parsed = UUID.fromString(uuid);
        if (overflowed || (size * 2 == pairs.length && !madeRoom())) return;
        pairs[size * 2] = parsed.getMostSignificantBits();
        pairs[size * 2 + 1] = parsed.getLeastSignificantBits();
        size++;
    }

    public int size() throws SourceException {
        compact();
        if (overflowed || size > limit) throw SourceException.tooLarge("more than " + limit + " players");
        return size;
    }

    @Override
    public Iterator<String> iterator() {
        if (compacted != size) throw new IllegalStateException("Read the size before iterating the player keys");
        return new Iterator<String>() {
            private int next;

            @Override
            public boolean hasNext() {
                return next < compacted;
            }

            @Override
            public String next() {
                if (!hasNext()) throw new NoSuchElementException();
                int at = next++;
                return new UUID(pairs[at * 2], pairs[at * 2 + 1]).toString();
            }
        };
    }

    private boolean madeRoom() {
        compact();
        int capacity = pairs.length / 2;
        if (size * 2 <= capacity) return true;
        int grown = (int) Math.min((long) limit * GROWTH_LIMIT_FACTOR, (long) capacity * 2);
        if (grown > capacity) {
            pairs = Arrays.copyOf(pairs, grown * 2);
            return true;
        }
        if (size < capacity) return true;
        overflowed = true;
        return false;
    }

    private void compact() {
        if (compacted == size) return;
        sort();
        int unique = 0;
        for (int index = 0; index < size; index++) {
            if (unique > 0 && compare(index, unique - 1) == 0) continue;
            pairs[unique * 2] = pairs[index * 2];
            pairs[unique * 2 + 1] = pairs[index * 2 + 1];
            unique++;
        }
        size = unique;
        compacted = unique;
    }

    private void sort() {
        for (int root = size / 2 - 1; root >= 0; root--) siftDown(root, size);
        for (int end = size - 1; end > 0; end--) {
            swap(0, end);
            siftDown(0, end);
        }
    }

    private void siftDown(int root, int end) {
        int parent = root;
        while (true) {
            int child = parent * 2 + 1;
            if (child >= end) return;
            if (child + 1 < end && compare(child + 1, child) > 0) child++;
            if (compare(parent, child) >= 0) return;
            swap(parent, child);
            parent = child;
        }
    }

    private int compare(int left, int right) {
        int high = Long.compare(pairs[left * 2], pairs[right * 2]);
        return high != 0 ? high : Long.compare(pairs[left * 2 + 1], pairs[right * 2 + 1]);
    }

    private void swap(int left, int right) {
        for (int part = 0; part < 2; part++) {
            long kept = pairs[left * 2 + part];
            pairs[left * 2 + part] = pairs[right * 2 + part];
            pairs[right * 2 + part] = kept;
        }
    }
}
