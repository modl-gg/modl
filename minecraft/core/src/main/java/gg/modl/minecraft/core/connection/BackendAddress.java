package gg.modl.minecraft.core.connection;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;

@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class BackendAddress {
    String scheme;
    NetworkAddress address;
    String basePath;

    public static BackendAddress http(NetworkAddress address) {
        return new BackendAddress(HttpUrl.HTTP, address, "");
    }

    public static BackendAddress of(HttpUrl url) {
        return new BackendAddress(url.getScheme(), url.getAddress(), url.basePath());
    }

    public boolean hasHost() {
        return address.hasHost();
    }

    public String toBaseUrl() {
        return scheme + "://" + authority() + basePath;
    }

    public String authority() {
        if (!hasHost()) throw new IllegalStateException("backend address has no host");
        return HttpUrl.authority(scheme, address);
    }

    public String resolve(String path) {
        return toBaseUrl() + (path.startsWith("/") ? path : "/" + path);
    }

    public String resolveReference(String reference) {
        if (reference == null || reference.trim().isEmpty()) {
            throw new IllegalArgumentException("missing URL");
        }
        String trimmed = reference.trim();
        if (HttpUrl.isPathReference(trimmed)) return resolve(trimmed);
        try {
            return HttpUrl.parse(trimmed).toString();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported URL '" + trimmed + "': " + e.getMessage(), e);
        }
    }

    public BackendEndpoint toEndpoint(String apiKey) {
        return BackendEndpoint.of(this, apiKey);
    }
}
