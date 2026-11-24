package gg.modl.minecraft.core.connection;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.ToString;
import lombok.Value;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class BackendEndpoint {
    private static final String REALTIME_PATH = "/v1/realtime/ws";

    BackendAddress address;
    @ToString.Exclude String apiKey;

    public static BackendEndpoint of(String baseUrl, String apiKey) {
        HttpUrl parsed;
        try {
            parsed = HttpUrl.parseBase(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported backend URL '" + baseUrl + "': " + e.getMessage(), e);
        }
        return of(BackendAddress.of(parsed), apiKey);
    }

    static BackendEndpoint of(BackendAddress address, String apiKey) {
        if (!address.hasHost()) throw new IllegalArgumentException("A backend endpoint needs a host");
        return new BackendEndpoint(address, apiKey == null ? "" : apiKey);
    }

    public String baseUrl() {
        return address.toBaseUrl();
    }

    public String resolve(String path) {
        return address.resolve(path);
    }

    public String resolveReference(String reference) {
        return address.resolveReference(reference);
    }

    public URI realtimeUri() {
        return realtimeUri(address.authority());
    }

    public URI resolvedRealtimeUri() throws UnknownHostException {
        URI uri = realtimeUri();
        if (uri.getHost() != null) return uri;
        NetworkAddress resolved = NetworkAddress.of(
                InetAddress.getByName(address.getAddress().getHost()).getHostAddress(), address.getAddress().getPort());
        return realtimeUri(HttpUrl.authority(address.getScheme(), resolved));
    }

    private URI realtimeUri(String authority) {
        String scheme = HttpUrl.HTTPS.equals(address.getScheme()) ? "wss" : "ws";
        return URI.create(scheme + "://" + authority + address.getBasePath() + REALTIME_PATH);
    }
}
