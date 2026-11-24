package gg.modl.backend.ticket.service;

import gg.modl.backend.storage.repository.StorageFile;
import gg.modl.backend.storage.repository.StorageFileRepository;
import gg.modl.backend.storage.service.StoredObjectUrlResolver;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TicketUploads {
    private static final String URL_KEY = "url";

    private final StoredObjectUrlResolver urlResolver;
    private final StorageFileRepository storageFileRepository;

    public record Attached(List<Object> attachments, @Nullable Map<String, Object> formData) {
    }

    public List<Object> attach(@Nullable List<Object> attachments) {
        return attach(attachments, null).attachments();
    }

    public Attached attach(@Nullable List<Object> attachments, @Nullable Map<String, Object> formData) {
        List<Object> submitted = attachments == null ? List.of() : attachments;
        Set<String> keys = new TreeSet<>();
        collectKeys(submitted, keys);
        if (formData != null) {
            collectKeys(formData.values(), keys);
        }
        if (keys.isEmpty() || storageFileRepository.markAttached(keys) == keys.size()) {
            return new Attached(submitted, formData);
        }

        Set<String> missing = new TreeSet<>(keys);
        storageFileRepository.findByKeys(keys).stream().map(StorageFile::key).forEach(missing::remove);
        return new Attached(withoutMissing(submitted, missing), formData == null ? null : withoutMissing(formData, missing));
    }

    private void collectKeys(Object value, Set<String> keys) {
        switch (value) {
            case String text -> keyOf(text).ifPresent(keys::add);
            case Map<?, ?> map -> collectKeys(map.values(), keys);
            case Collection<?> items -> {
                for (Object item : items) {
                    if (item != null) {
                        collectKeys(item, keys);
                    }
                }
            }
            default -> {
            }
        }
    }

    private List<Object> withoutMissing(Collection<?> items, Set<String> missing) {
        List<Object> kept = new ArrayList<>();
        for (Object item : items) {
            if (!pointsToMissingFile(item, missing)) {
                kept.add(cleaned(item, missing));
            }
        }
        return kept;
    }

    private Map<String, Object> withoutMissing(Map<?, ?> entries, Set<String> missing) {
        Map<String, Object> kept = new LinkedHashMap<>();
        entries.forEach((key, value) -> {
            if (!pointsToMissingFile(value, missing)) {
                kept.put(String.valueOf(key), cleaned(value, missing));
            }
        });
        return kept;
    }

    @Nullable
    private Object cleaned(@Nullable Object value, Set<String> missing) {
        if (value instanceof Map<?, ?> map) {
            return withoutMissing(map, missing);
        }
        return value instanceof Collection<?> items ? withoutMissing(items, missing) : value;
    }

    private boolean pointsToMissingFile(@Nullable Object value, Set<String> missing) {
        Object location = value instanceof Map<?, ?> map ? map.get(URL_KEY) : value;
        return location instanceof String url && keyOf(url).filter(missing::contains).isPresent();
    }

    private Optional<String> keyOf(String value) {
        return urlResolver.keyOf(value.trim());
    }
}
