package gg.modl.backend.hostedimport.mapping;


public final class PunishmentKeepers {
    private static final long EMPTY = 0L;
    private static final long EMPTY_SUBSTITUTE = 1L;
    private static final int INITIAL_CAPACITY = 1 << 12;

    private long[] keys = new long[INITIAL_CAPACITY];
    private long[] owners = new long[INITIAL_CAPACITY];
    private int size;

    public void offer(String punishmentId, String playerUuid) {
        long key = nonEmpty(ImportedIds.hash64(punishmentId));
        long owner = ImportedIds.hash64(playerUuid);
        if ((size + 1) * 2L > keys.length) {
            grow();
        }
        int slot = slotOf(key, keys);
        if (keys[slot] == EMPTY) {
            keys[slot] = key;
            owners[slot] = owner;
            size++;
        }
    }

    public boolean isKeptBy(String punishmentId, String playerUuid) {
        long key = nonEmpty(ImportedIds.hash64(punishmentId));
        int slot = slotOf(key, keys);
        return keys[slot] == EMPTY || owners[slot] == ImportedIds.hash64(playerUuid);
    }

    public int size() {
        return size;
    }

    private static int slotOf(long key, long[] table) {
        int mask = table.length - 1;
        int slot = (int) (key ^ (key >>> 32)) & mask;
        while (table[slot] != EMPTY && table[slot] != key) {
            slot = (slot + 1) & mask;
        }
        return slot;
    }

    private void grow() {
        long[] oldKeys = keys;
        long[] oldOwners = owners;
        keys = new long[oldKeys.length * 2];
        owners = new long[oldKeys.length * 2];
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != EMPTY) {
                int slot = slotOf(oldKeys[i], keys);
                keys[slot] = oldKeys[i];
                owners[slot] = oldOwners[i];
            }
        }
    }

    private static long nonEmpty(long key) {
        return key == EMPTY ? EMPTY_SUBSTITUTE : key;
    }

    @Override
    public String toString() {
        return "PunishmentKeepers[" + size + "/" + keys.length + "]";
    }
}
