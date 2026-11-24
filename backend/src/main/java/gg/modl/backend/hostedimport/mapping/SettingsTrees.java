package gg.modl.backend.hostedimport.mapping;

import gg.modl.backend.hostedimport.archive.ArchiveDocument;
import gg.modl.backend.hostedimport.storage.ImportedUrls;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SettingsTrees {
    private static final String MONGO_ID = "_id";
    private static final String ID = "id";

    private SettingsTrees() {
    }

    static Map<String, Object> withIds(Map<String, @Nullable Object> source) {
        Map<String, Object> plain = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!MONGO_ID.equals(key)) {
                plain.put(key, tree(value));
            }
        });
        if (!plain.containsKey(ID) && source.containsKey(MONGO_ID)) {
            plain.put(ID, ArchiveDocument.idString(source.get(MONGO_ID)));
        } else if (plain.get(ID) != null && !(plain.get(ID) instanceof String)) {
            plain.put(ID, ArchiveDocument.idString(source.get(ID)));
        }
        return plain;
    }

    private static @Nullable Object tree(@Nullable Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, @Nullable Object> nested = new LinkedHashMap<>();
            map.forEach((key, item) -> nested.put(String.valueOf(key), item));
            return withIds(nested);
        }
        if (value instanceof List<?> list) {
            List<Object> items = new ArrayList<>(list.size());
            list.forEach(item -> items.add(tree(item)));
            return items;
        }
        Object plain = ImportedUrls.plain(value);
        return plain instanceof Instant instant ? instant.toString() : plain;
    }
}
