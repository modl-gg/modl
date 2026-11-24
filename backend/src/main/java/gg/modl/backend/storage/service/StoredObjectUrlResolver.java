package gg.modl.backend.storage.service;

import gg.modl.backend.infrastructure.web.HttpOrigin;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.storage.ObjectStorage;
import java.net.URI;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StoredObjectUrlResolver {
    private final ObjectStorage objectStorage;
    private final PublicUrls publicUrls;

    public Optional<String> keyOf(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        return objectStorage.keyFromPublicUrl(url).or(() -> sameOriginPath(url).flatMap(objectStorage::keyFromPublicUrl));
    }

    private Optional<String> sameOriginPath(String url) {
        return HttpOrigin.parse(url)
            .filter(origin -> origin.equals(publicUrls.publicOrigin()))
            .flatMap(origin -> rawPath(url));
    }

    private static Optional<String> rawPath(String url) {
        try {
            return Optional.ofNullable(URI.create(url).getRawPath());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
