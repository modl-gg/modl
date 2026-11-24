package gg.modl.backend.hostedimport.job;

import gg.modl.backend.hostedimport.mapping.ArchiveIndex;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class ArchiveSession {
    private final AtomicReference<@Nullable LoadedArchive> loaded = new AtomicReference<>();

    public Optional<ArchiveIndex> indexFor(String importId) {
        LoadedArchive current = loaded.get();
        return current != null && current.importId().equals(importId) ? Optional.of(current.index()) : Optional.empty();
    }

    void remember(String importId, ArchiveIndex index) {
        loaded.set(new LoadedArchive(importId, index));
    }

    public void forget() {
        loaded.set(null);
    }

    private record LoadedArchive(String importId, ArchiveIndex index) {
    }
}
