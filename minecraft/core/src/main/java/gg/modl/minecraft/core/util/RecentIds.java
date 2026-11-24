package gg.modl.minecraft.core.util;

import java.util.LinkedHashSet;
import java.util.Set;

public final class RecentIds {
    private final int capacity;
    private final Set<String> ids = new LinkedHashSet<>();

    public RecentIds(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public synchronized boolean markIfNew(String id) {
        if (id == null || id.trim().isEmpty()) {
            return true;
        }
        if (ids.contains(id)) {
            return false;
        }
        ids.add(id);
        while (ids.size() > capacity) {
            String oldest = ids.iterator().next();
            ids.remove(oldest);
        }
        return true;
    }

    public synchronized boolean contains(String id) {
        if (id == null || id.trim().isEmpty()) {
            return false;
        }
        return ids.contains(id);
    }
}
