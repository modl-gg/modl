package gg.modl.backend.hostedimport.intake;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public final class LinkPolicy {
    static final int MAX_LENGTH = 4096;
    private static final Pattern HOST = Pattern.compile("^([a-z0-9][a-z0-9-]{1,61}\\.)?s3\\.[a-z]{2}-[a-z]+-[0-9]{3}\\.backblazeb2\\.com$");
    private static final String SIGNATURE_PARAMETER = "X-Amz-Signature=";
    private static final String HTTPS = "https";
    private static final int HTTPS_PORT = 443;
    private static final int ASCII_LIMIT = 128;

    public Optional<URI> check(String link) {
        if (link == null || link.isEmpty() || link.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(link.trim());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        return isAllowed(uri) ? Optional.of(uri) : Optional.empty();
    }

    private static boolean isAllowed(URI uri) {
        if (!HTTPS.equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            return false;
        }
        if (uri.getPort() != -1 && uri.getPort() != HTTPS_PORT) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || !host.equals(host.toLowerCase(Locale.ROOT)) || !isAscii(host) || !HOST.matcher(host).matches()) {
            return false;
        }
        String query = uri.getRawQuery();
        return query != null && query.contains(SIGNATURE_PARAMETER);
    }

    private static boolean isAscii(String value) {
        return value.chars().allMatch(c -> c < ASCII_LIMIT);
    }
}
