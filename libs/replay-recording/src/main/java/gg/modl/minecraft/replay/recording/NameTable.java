package gg.modl.minecraft.replay.recording;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

final class NameTable {
    static final int MAX_NAMES = 65536;
    static final String UNKNOWN = "minecraft:unknown";

    private final Map<Object, Integer> byKey = new ConcurrentHashMap<>();
    private final Map<String, Integer> byName = new ConcurrentHashMap<>();
    private final List<String> names = new ArrayList<>();

    NameTable() {
        intern(UNKNOWN);
    }

    int id(Object key, Supplier<String> name) {
        Integer known = byKey.get(key);
        if (known != null) return known;
        int id = intern(name.get());
        if (byKey.size() < MAX_NAMES) byKey.put(key, id);
        return id;
    }

    int intern(String name) {
        Integer known = byName.get(name);
        if (known != null) return known;
        synchronized (names) {
            Integer again = byName.get(name);
            if (again != null) return again;
            if (names.size() >= MAX_NAMES) return 0;
            int id = names.size();
            names.add(name);
            byName.put(name, id);
            return id;
        }
    }

    String name(int id) {
        synchronized (names) {
            return (id >= 0 && id < names.size()) ? names.get(id) : UNKNOWN;
        }
    }
}
