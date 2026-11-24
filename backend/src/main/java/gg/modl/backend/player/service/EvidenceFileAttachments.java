package gg.modl.backend.player.service;

import gg.modl.backend.storage.repository.StorageFileRepository;
import gg.modl.backend.storage.service.StoredObjectUrlResolver;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EvidenceFileAttachments {
    private final StoredObjectUrlResolver urlResolver;
    private final StorageFileRepository storageFileRepository;

    List<String> keysOf(Collection<String> urls) {
        return urls.stream()
            .filter(Objects::nonNull)
            .map(urlResolver::keyOf)
            .flatMap(Optional::stream)
            .distinct()
            .sorted()
            .toList();
    }

    void markAttached(List<String> storageKeys) {
        if (!storageKeys.isEmpty()) {
            storageFileRepository.markAttached(storageKeys);
        }
    }
}
