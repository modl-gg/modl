package gg.modl.backend.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import java.util.Locale;
import java.util.Optional;

public record HttpOrigin(String scheme, String host, int port) {
    private static final String HTTP = "http";
    private static final String HTTPS = "https";
    private static final String SCHEME_SEPARATOR = "://";
    private static final String AUTHORITY_TERMINATORS = "/?#";
    private static final int HTTP_DEFAULT_PORT = 80;
    private static final int HTTPS_DEFAULT_PORT = 443;
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65_535;
    private static final int MAX_PORT_DIGITS = 5;
    private static final String LOCALHOST = "localhost";
    private static final String LOCALHOST_SUFFIX = ".localhost";
    private static final String IPV4_LOOPBACK_PREFIX = "127.";
    private static final String IPV6_LOOPBACK = "[::1]";

    public HttpOrigin {
        scheme = scheme.toLowerCase(Locale.ROOT);
        host = host.toLowerCase(Locale.ROOT);
        port = port < 0 ? defaultPort(scheme) : port;
    }

    public static Optional<HttpOrigin> parse(@Nullable String value) {
        return attempt(value, false);
    }

    public static Optional<HttpOrigin> parseExact(@Nullable String value) {
        return attempt(value, true);
    }

    public static HttpOrigin requireExact(@Nullable String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("is empty");
        }
        return read(value.trim(), true);
    }

    public static HttpOrigin of(HttpServletRequest request) {
        return new HttpOrigin(request.getScheme(), bracketedIfIpv6(request.getServerName()), request.getServerPort());
    }

    public boolean isSecure() {
        return HTTPS.equals(scheme);
    }

    public boolean isLoopback() {
        return namesLoopback() || isIpv4Loopback(host) || IPV6_LOOPBACK.equals(host);
    }

    public boolean namesLoopback() {
        return LOCALHOST.equals(host) || host.endsWith(LOCALHOST_SUFFIX);
    }

    public HttpOrigin withHost(String replacement) {
        return new HttpOrigin(scheme, replacement, port);
    }

    public boolean sameHost(HttpOrigin other) {
        return host.equals(other.host);
    }

    public String value() {
        return port == defaultPort(scheme) ? scheme + "://" + host : scheme + "://" + host + ":" + port;
    }

    @Override
    public String toString() {
        return value();
    }

    private static Optional<HttpOrigin> attempt(@Nullable String value, boolean exact) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(read(value.trim(), exact));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private static HttpOrigin read(String value, boolean exact) {
        int schemeEnd = value.indexOf(SCHEME_SEPARATOR);
        String scheme = schemeEnd > 0 ? value.substring(0, schemeEnd).toLowerCase(Locale.ROOT) : "";
        if (!HTTP.equals(scheme) && !HTTPS.equals(scheme)) {
            throw new IllegalArgumentException("must start with http:// or https://");
        }
        String rest = value.substring(schemeEnd + SCHEME_SEPARATOR.length());
        int authorityEnd = indexOfAny(rest, AUTHORITY_TERMINATORS);
        String authority = authorityEnd < 0 ? rest : rest.substring(0, authorityEnd);
        String remainder = authorityEnd < 0 ? "" : rest.substring(authorityEnd);
        if (exact && !remainder.isEmpty() && !"/".equals(remainder)) {
            throw new IllegalArgumentException("must be only scheme, host, and port, without a path, query, or fragment");
        }
        if (authority.indexOf('@') >= 0) {
            throw new IllegalArgumentException("must not contain a user name or password");
        }
        return readAuthority(scheme, authority);
    }

    private static HttpOrigin readAuthority(String scheme, String authority) {
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("has an unterminated IPv6 address");
            }
            String host = authority.substring(0, close + 1);
            requireIpv6Literal(host.substring(1, close));
            String afterHost = authority.substring(close + 1);
            if (afterHost.isEmpty()) {
                return new HttpOrigin(scheme, host, -1);
            }
            if (!afterHost.startsWith(":")) {
                throw new IllegalArgumentException("has unexpected characters after the IPv6 address");
            }
            return new HttpOrigin(scheme, host, port(afterHost.substring(1)));
        }
        int colon = authority.indexOf(':');
        if (colon != authority.lastIndexOf(':')) {
            throw new IllegalArgumentException("has more than one ':' in the host (put IPv6 addresses in brackets)");
        }
        String host = colon < 0 ? authority : authority.substring(0, colon);
        requireHostName(host);
        return new HttpOrigin(scheme, host, colon < 0 ? -1 : port(authority.substring(colon + 1)));
    }

    private static void requireHostName(String host) {
        if (host.isEmpty()) {
            throw new IllegalArgumentException("is missing a host name");
        }
        for (int index = 0; index < host.length(); index++) {
            char character = host.charAt(index);
            boolean allowed = isAsciiLetterOrDigit(character) || character == '-' || character == '.' || character == '_';
            if (!allowed) {
                throw new IllegalArgumentException("has an invalid character '" + character + "' in the host name");
            }
        }
    }

    private static void requireIpv6Literal(String address) {
        if (address.isEmpty()) {
            throw new IllegalArgumentException("has an empty IPv6 address");
        }
        for (int index = 0; index < address.length(); index++) {
            char character = address.charAt(index);
            boolean allowed = Character.digit(character, 16) >= 0 || character == ':' || character == '.';
            if (!allowed) {
                throw new IllegalArgumentException("has an invalid character '" + character + "' in the IPv6 address");
            }
        }
    }

    private static int port(String digits) {
        if (digits.isEmpty() || digits.length() > MAX_PORT_DIGITS || !digits.chars().allMatch(HttpOrigin::isAsciiDigit)) {
            throw new IllegalArgumentException("has an invalid port '" + digits + "' (use a number from 1 to 65535)");
        }
        int port = Integer.parseInt(digits);
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException("has port " + port + ", which is outside 1 to 65535");
        }
        return port;
    }

    private static boolean isIpv4Loopback(String host) {
        return host.startsWith(IPV4_LOOPBACK_PREFIX)
            && host.chars().allMatch(character -> isAsciiDigit(character) || character == '.');
    }

    private static String bracketedIfIpv6(String serverName) {
        boolean bareIpv6 = serverName.indexOf(':') >= 0 && !serverName.startsWith("[");
        return bareIpv6 ? "[" + serverName + "]" : serverName;
    }

    private static int indexOfAny(String value, String characters) {
        for (int index = 0; index < value.length(); index++) {
            if (characters.indexOf(value.charAt(index)) >= 0) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isAsciiLetterOrDigit(char character) {
        return isAsciiDigit(character) || (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z');
    }

    private static boolean isAsciiDigit(int character) {
        return character >= '0' && character <= '9';
    }

    private static int defaultPort(String scheme) {
        return HTTPS.equals(scheme) ? HTTPS_DEFAULT_PORT : HTTP_DEFAULT_PORT;
    }
}
