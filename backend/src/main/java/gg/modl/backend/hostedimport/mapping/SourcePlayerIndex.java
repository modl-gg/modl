package gg.modl.backend.hostedimport.mapping;

import org.jspecify.annotations.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SourcePlayerIndex {
    private static final int INITIAL_CAPACITY = 1 << 12;
    private static final int OBJECT_ID_LENGTH = 24;
    private static final int HIGH_HEX_DIGITS = 16;
    private static final int HEX_RADIX = 16;

    private long[] idHigh = new long[INITIAL_CAPACITY];
    private int[] idLow = new int[INITIAL_CAPACITY];
    private long[] uuidMost = new long[INITIAL_CAPACITY];
    private long[] uuidLeast = new long[INITIAL_CAPACITY];
    private boolean[] used = new boolean[INITIAL_CAPACITY];
    private int compactSize;
    private final Map<String, UUID> otherIds = new HashMap<>();

    public void putIfAbsent(String sourceId, UUID uuid) {
        if (!isObjectId(sourceId)) {
            otherIds.putIfAbsent(sourceId, uuid);
            return;
        }
        if ((compactSize + 1) * 2L > used.length) {
            grow();
        }
        long high = high(sourceId);
        int low = low(sourceId);
        int slot = slotOf(high, low, idHigh, idLow, used);
        if (!used[slot]) {
            store(slot, high, low, uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
            compactSize++;
        }
    }

    @Nullable
    public String uuidOf(@Nullable String sourceId) {
        if (sourceId == null) {
            return null;
        }
        if (!isObjectId(sourceId)) {
            UUID other = otherIds.get(sourceId);
            return other == null ? null : other.toString();
        }
        int slot = slotOf(high(sourceId), low(sourceId), idHigh, idLow, used);
        return used[slot] ? new UUID(uuidMost[slot], uuidLeast[slot]).toString() : null;
    }

    public int size() {
        return compactSize + otherIds.size();
    }

    private void store(int slot, long high, int low, long most, long least) {
        used[slot] = true;
        idHigh[slot] = high;
        idLow[slot] = low;
        uuidMost[slot] = most;
        uuidLeast[slot] = least;
    }

    private void grow() {
        long[] oldHigh = idHigh;
        int[] oldLow = idLow;
        long[] oldMost = uuidMost;
        long[] oldLeast = uuidLeast;
        boolean[] oldUsed = used;
        int capacity = oldUsed.length * 2;
        idHigh = new long[capacity];
        idLow = new int[capacity];
        uuidMost = new long[capacity];
        uuidLeast = new long[capacity];
        used = new boolean[capacity];
        for (int i = 0; i < oldUsed.length; i++) {
            if (oldUsed[i]) {
                store(slotOf(oldHigh[i], oldLow[i], idHigh, idLow, used), oldHigh[i], oldLow[i], oldMost[i], oldLeast[i]);
            }
        }
    }

    private static int slotOf(long high, int low, long[] highs, int[] lows, boolean[] occupied) {
        int mask = occupied.length - 1;
        long mixed = high * 0x9E3779B97F4A7C15L ^ low;
        int slot = (int) (mixed ^ (mixed >>> 32)) & mask;
        while (occupied[slot] && (highs[slot] != high || lows[slot] != low)) {
            slot = (slot + 1) & mask;
        }
        return slot;
    }

    private static boolean isObjectId(String sourceId) {
        if (sourceId.length() != OBJECT_ID_LENGTH) {
            return false;
        }
        for (int i = 0; i < OBJECT_ID_LENGTH; i++) {
            char c = sourceId.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static long high(String objectId) {
        return Long.parseUnsignedLong(objectId.substring(0, HIGH_HEX_DIGITS), HEX_RADIX);
    }

    private static int low(String objectId) {
        return Integer.parseUnsignedInt(objectId.substring(HIGH_HEX_DIGITS), HEX_RADIX);
    }
}
