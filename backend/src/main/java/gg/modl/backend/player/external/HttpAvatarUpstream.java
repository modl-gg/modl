package gg.modl.backend.player.external;

import gg.modl.backend.infrastructure.config.OutboundHttpConfig;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
final class HttpAvatarUpstream implements AvatarUpstream {
    private static final int MAX_AVATAR_BYTES = 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    private final RestClient restClient = OutboundHttpConfig.restClient(CONNECT_TIMEOUT, READ_TIMEOUT);

    @Override
    public byte @Nullable [] fetch(String uuid, int size, boolean overlay) {
        URI crafatar = UriComponentsBuilder.fromUriString("https://crafatar.com")
            .pathSegment("avatars", uuid)
            .queryParam("size", size)
            .queryParam("overlay", overlay)
            .build()
            .encode()
            .toUri();
        byte[] body = fetchOrNull(crafatar);
        if (body != null) {
            return body;
        }
        URI minotar = UriComponentsBuilder.fromUriString("https://minotar.net")
            .pathSegment("avatar", uuid, String.valueOf(size))
            .build()
            .encode()
            .toUri();
        return fetchOrNull(minotar);
    }

    private byte @Nullable [] fetchOrNull(URI url) {
        try {
            return restClient.get().uri(url).exchange((request, response) -> readImage(url, response));
        } catch (RuntimeException e) {
            log.debug("Avatar upstream {} failed", url, e);
            return null;
        }
    }

    private static byte @Nullable [] readImage(URI url, ClientHttpResponse response) throws IOException {
        if (!response.getStatusCode().is2xxSuccessful()) {
            return null;
        }
        HttpHeaders headers = response.getHeaders();
        MediaType contentType = headers.getContentType();
        if (contentType != null && !"image".equalsIgnoreCase(contentType.getType())) {
            log.debug("Avatar upstream {} returned non-image content-type {}", url, contentType);
            return null;
        }
        if (headers.getContentLength() > MAX_AVATAR_BYTES) {
            return null;
        }
        try (InputStream body = response.getBody()) {
            byte[] read = body.readNBytes(MAX_AVATAR_BYTES + 1);
            return read.length == 0 || read.length > MAX_AVATAR_BYTES ? null : read;
        }
    }
}
