package gg.modl.backend.ai.http;

import gg.modl.backend.infrastructure.exception.ValidationException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiBaseUrlPolicy {
    public static final String INVALID = "AI_BASE_URL_INVALID";
    public static final String HTTP_PUBLIC = "AI_BASE_URL_HTTP_PUBLIC";
    public static final String BLOCKED = "AI_ADDRESS_BLOCKED";

    private static final int MAX_LENGTH = 2048;
    private static final String LOCALHOST = "localhost";
    private static final Pattern HOST_NAME = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*\\.?");
    private static final Pattern DOTTED_QUAD = Pattern.compile("(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");
    private static final Pattern NUMERIC_LABEL = Pattern.compile("(0x[0-9a-f]*|[0-9]+)");
    private static final Pattern ENCODED_SEPARATOR = Pattern.compile("%(2f|5c|2e)", Pattern.CASE_INSENSITIVE);

    private final AiAddressPolicy addressPolicy;

    public URI require(String raw) {
        Check check = check(raw);
        URI url = check.url();
        if (url == null) {
            String rejection = check.rejectionCode();
            throw new ValidationException(message(rejection), rejection);
        }
        return url;
    }

    public Check check(@Nullable String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_LENGTH || !printableWithoutSpaces(raw) || raw.indexOf('\\') >= 0) {
            return Check.rejected(INVALID);
        }
        URI parsed;
        try {
            parsed = new URI(raw);
        } catch (URISyntaxException e) {
            return Check.rejected(INVALID);
        }
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
        if (!parsed.isAbsolute() || parsed.isOpaque() || !(scheme.equals("http") || scheme.equals("https"))
            || parsed.getRawUserInfo() != null || parsed.getRawQuery() != null || parsed.getRawFragment() != null
            || parsed.getHost() == null) {
            return Check.rejected(INVALID);
        }
        String host = parsed.getHost().toLowerCase(Locale.ROOT);
        String path = parsed.getRawPath() == null ? "" : parsed.getRawPath();
        int port = parsed.getPort();
        if (!validHost(host) || !validPath(path) || port == 0 || port > 65535) {
            return Check.rejected(INVALID);
        }
        Optional<String> addressRejection = literalRejection(host, scheme.equals("http"));
        if (addressRejection.isPresent()) {
            return Check.rejected(addressRejection.get());
        }
        return Check.accepted(normalised(scheme, host, port, path));
    }

    private Optional<String> literalRejection(String host, boolean plainHttp) {
        if (host.equals(LOCALHOST)) {
            return Optional.empty();
        }
        if (!isAddressLiteral(host)) {
            return Optional.empty();
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            return switch (addressPolicy.check(address, plainHttp)) {
                case ALLOWED -> Optional.empty();
                case BLOCKED -> Optional.of(BLOCKED);
                case PLAIN_HTTP_PUBLIC -> Optional.of(HTTP_PUBLIC);
            };
        } catch (UnknownHostException e) {
            return Optional.of(INVALID);
        }
    }

    private static boolean validHost(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.length() > 2 && host.substring(1, host.length() - 1).matches("[0-9a-f:.]+");
        }
        if (!HOST_NAME.matcher(host).matches()) {
            return false;
        }
        String[] labels = host.split("\\.");
        String last = labels[labels.length - 1];
        return !NUMERIC_LABEL.matcher(last).matches() || DOTTED_QUAD.matcher(host).matches();
    }

    private static boolean validPath(String path) {
        if (ENCODED_SEPARATOR.matcher(path).find()) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAddressLiteral(String host) {
        return host.startsWith("[") || DOTTED_QUAD.matcher(host).matches();
    }

    private static boolean printableWithoutSpaces(String raw) {
        return raw.chars().allMatch(character -> character > 0x20 && character < 0x7F);
    }

    private static URI normalised(String scheme, String host, int port, String path) {
        boolean defaultPort = port == -1 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
        String trimmedPath = path;
        while (trimmedPath.endsWith("/")) {
            trimmedPath = trimmedPath.substring(0, trimmedPath.length() - 1);
        }
        return URI.create(scheme + "://" + host + (defaultPort ? "" : ":" + port) + trimmedPath);
    }

    private static String message(String code) {
        return switch (code) {
            case HTTP_PUBLIC -> "Plain http is allowed only for local and private addresses; use https";
            case BLOCKED -> "This address is not allowed";
            default -> "The base URL is not valid";
        };
    }

    public record Check(@Nullable URI url, @Nullable String rejection) {
        public Check {
            if ((url == null) == (rejection == null)) {
                throw new IllegalArgumentException("A base URL check is either accepted with a URL or rejected with a code");
            }
        }

        static Check accepted(URI url) {
            return new Check(url, null);
        }

        static Check rejected(String code) {
            return new Check(null, code);
        }

        public Optional<URI> validUrl() {
            return Optional.ofNullable(url);
        }

        String rejectionCode() {
            if (rejection == null) {
                throw new IllegalStateException("An accepted base URL check has no rejection code");
            }
            return rejection;
        }
    }
}
