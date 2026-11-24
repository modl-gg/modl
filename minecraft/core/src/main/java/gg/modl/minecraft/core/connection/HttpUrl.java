package gg.modl.minecraft.core.connection;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class HttpUrl {
    static final String HTTP = "http";
    static final String HTTPS = "https";
    private static final int HTTP_PORT = 80;
    private static final int HTTPS_PORT = 443;
    private static final Pattern PORT_DIGITS = Pattern.compile("\\d{1,5}");

    String scheme;
    NetworkAddress address;
    String rawPath;
    String rawQuery;
    String rawFragment;

    public static HttpUrl parse(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) throw new IllegalArgumentException("is empty");
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!HTTP.equals(scheme) && !HTTPS.equals(scheme)) {
            throw new IllegalArgumentException("must start with http:// or https://");
        }
        String authority = uri.getRawAuthority();
        if (uri.isOpaque() || authority == null || authority.isEmpty()) {
            throw new IllegalArgumentException("must contain a host name");
        }
        if (authority.indexOf('@') >= 0) throw new IllegalArgumentException("must not contain credentials");
        return new HttpUrl(scheme, parseAuthority(scheme, authority), uri.getRawPath() == null ? "" : uri.getRawPath(),
                uri.getRawQuery(), uri.getRawFragment());
    }

    public static HttpUrl parseBase(@Nullable String raw) {
        HttpUrl url = parse(raw);
        if (url.rawQuery != null || url.rawFragment != null) {
            throw new IllegalArgumentException("must not contain a query or fragment");
        }
        return url;
    }

    public static boolean isAbsolute(@Nullable String raw) {
        try {
            parse(raw);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public static boolean isPathReference(String reference) {
        return reference != null && reference.startsWith("/") && !reference.startsWith("//");
    }

    static int defaultPort(String scheme) {
        return HTTPS.equals(scheme) ? HTTPS_PORT : HTTP_PORT;
    }

    static String authority(String scheme, NetworkAddress address) {
        return address.getPort() == defaultPort(scheme) ? address.urlHost() : address.authority();
    }

    static String stripTrailingSlashes(String path) {
        if (path == null) return "";
        String stripped = path;
        while (stripped.endsWith("/")) stripped = stripped.substring(0, stripped.length() - 1);
        return stripped;
    }

    public boolean isOrigin() {
        return (rawPath.isEmpty() || "/".equals(rawPath)) && rawQuery == null && rawFragment == null;
    }

    public String basePath() {
        return stripTrailingSlashes(rawPath);
    }

    public String origin() {
        return scheme + "://" + authority(scheme, address);
    }

    @Override
    public String toString() {
        return origin() + rawPath + (rawQuery == null ? "" : "?" + rawQuery) + (rawFragment == null ? "" : "#" + rawFragment);
    }

    private static NetworkAddress parseAuthority(String scheme, String authority) {
        String host;
        String port;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) throw new IllegalArgumentException("has an unterminated IPv6 address");
            host = authority.substring(0, close + 1);
            String rest = authority.substring(close + 1);
            if (!rest.isEmpty() && !rest.startsWith(":")) throw new IllegalArgumentException("has an invalid host name");
            port = rest.isEmpty() ? "" : rest.substring(1);
        } else {
            int colon = authority.indexOf(':');
            if (colon >= 0 && authority.indexOf(':', colon + 1) >= 0) {
                throw new IllegalArgumentException("must put an IPv6 address in brackets, e.g. http://[::1]:8080");
            }
            host = colon < 0 ? authority : authority.substring(0, colon);
            port = colon < 0 ? "" : authority.substring(colon + 1);
        }
        String normalizedHost;
        try {
            normalizedHost = NetworkAddress.normalizeHost(host);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("has an invalid host name '" + host + "'");
        }
        if (normalizedHost.isEmpty()) throw new IllegalArgumentException("must contain a host name");
        return NetworkAddress.of(normalizedHost, parsePort(scheme, port));
    }

    private static int parsePort(String scheme, String port) {
        if (port.isEmpty()) return defaultPort(scheme);
        if (!PORT_DIGITS.matcher(port).matches()) throw new IllegalArgumentException("has an invalid port '" + port + "'");
        int parsed = Integer.parseInt(port);
        if (!NetworkAddress.isValidPort(parsed)) {
            throw new IllegalArgumentException("has port " + parsed + ", which is not between "
                    + NetworkAddress.MIN_PORT + " and " + NetworkAddress.MAX_PORT);
        }
        return parsed;
    }
}
