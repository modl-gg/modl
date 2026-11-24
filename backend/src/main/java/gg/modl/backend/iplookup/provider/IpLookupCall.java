package gg.modl.backend.iplookup.provider;

import org.jspecify.annotations.Nullable;
import java.net.URI;
import java.util.Map;

public record IpLookupCall(URI uri, Map<String, String> headers, @Nullable String jsonBody) {
    public IpLookupCall {
        headers = Map.copyOf(headers);
    }

    public static IpLookupCall get(URI uri) {
        return new IpLookupCall(uri, Map.of(), null);
    }

    public static IpLookupCall get(URI uri, Map<String, String> headers) {
        return new IpLookupCall(uri, headers, null);
    }

    public static IpLookupCall postJson(URI uri, String jsonBody) {
        return new IpLookupCall(uri, Map.of(), jsonBody);
    }

    @Override
    public String toString() {
        return "IpLookupCall[host=" + uri.getHost() + "]";
    }
}
