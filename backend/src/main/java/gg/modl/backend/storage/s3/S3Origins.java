package gg.modl.backend.storage.s3;

import gg.modl.backend.storage.StorageProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

public final class S3Origins {
    private static final String AWS_GLOBAL_ENDPOINT = "https://s3.amazonaws.com";

    private S3Origins() {
    }

    public static Set<String> of(StorageProperties.S3 settings) {
        Set<String> origins = new LinkedHashSet<>();
        originOf(settings.publicBaseUrl()).ifPresent(origins::add);
        settings.endpointOverride().ifPresentOrElse(
            endpoint -> originOf(endpoint).ifPresent(origins::add),
            () -> {
                origins.add(AWS_GLOBAL_ENDPOINT);
                origins.add("https://s3.%s.amazonaws.com".formatted(settings.region()));
            });
        return origins;
    }

    public static Optional<String> originOf(String url) {
        try {
            URI uri = new URI(url);
            boolean webOrigin = uri.getHost() != null && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()));
            if (!webOrigin) {
                return Optional.empty();
            }
            String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
            return Optional.of(uri.getScheme() + "://" + uri.getHost() + port);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }
}
