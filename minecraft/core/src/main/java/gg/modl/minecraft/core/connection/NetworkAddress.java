package gg.modl.minecraft.core.connection;

import gg.modl.address.IpLiteral;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;

import java.net.InetAddress;
import java.util.Locale;
import java.util.regex.Pattern;

@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class NetworkAddress {
    public static final int MIN_PORT = 1;
    public static final int MAX_PORT = 65535;

    private static final String LOOPBACK_IPV4 = "127.0.0.1";
    private static final String LOCALHOST = "localhost";
    private static final int MAX_LABEL_LENGTH = 63;
    private static final Pattern LABEL = Pattern.compile("[a-z0-9_](?:[a-z0-9_-]*[a-z0-9_])?");
    private static final Pattern HOST_WITH_PORT = Pattern.compile("[^:]+:\\d*");
    private static final Pattern NUMERIC_LABEL = Pattern.compile("[0-9]+|0x[0-9a-f]*");

    String host;
    int port;

    public static NetworkAddress of(String host, int port) {
        return new NetworkAddress(normalizeHost(host), requireValidPort(port));
    }

    public static boolean isValidPort(int port) {
        return port >= MIN_PORT && port <= MAX_PORT;
    }

    public static int requireValidPort(int port) {
        if (!isValidPort(port)) {
            throw new IllegalArgumentException("port " + port + " is not between " + MIN_PORT + " and " + MAX_PORT);
        }
        return port;
    }

    public static String normalizeHost(String raw) {
        String trimmed = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) return "";
        boolean bracketed = trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() > 2;
        String candidate = bracketed ? trimmed.substring(1, trimmed.length() - 1) : trimmed;
        if (IpLiteral.isIpv6Literal(candidate)) return candidate;
        if (bracketed) throw invalidHost(raw, "is not a valid IPv6 address");
        if (HOST_WITH_PORT.matcher(candidate).matches()) {
            throw invalidHost(raw, "includes a port; put the port in its own setting");
        }
        if (endsInANumber(candidate)) {
            if (!IpLiteral.isIpv4Literal(candidate)) throw invalidHost(raw, "is not a valid IPv4 address");
            return candidate;
        }
        if (!isHostName(candidate)) throw invalidHost(raw, "is not a valid host name or IP address");
        return candidate;
    }

    public boolean hasHost() {
        return !host.isEmpty();
    }

    public boolean isWildcard() {
        if (host.isEmpty()) return true;
        InetAddress literal = IpLiteral.parseOrNull(host);
        return literal != null && literal.isAnyLocalAddress();
    }

    public boolean isLoopback() {
        if (LOCALHOST.equals(host) || (LOCALHOST + ".").equals(host)) return true;
        InetAddress literal = IpLiteral.parseOrNull(host);
        return literal != null && literal.isLoopbackAddress();
    }

    public NetworkAddress withHost(String newHost) {
        return of(newHost, port);
    }

    public String connectHost() {
        return isWildcard() ? LOOPBACK_IPV4 : host;
    }

    public String urlHost() {
        return urlHost(host);
    }

    public String authority() {
        return urlHost() + ":" + port;
    }

    static String urlHost(String host) {
        return host.indexOf(':') >= 0 ? "[" + host + "]" : host;
    }

    private static boolean endsInANumber(String candidate) {
        String name = candidate.endsWith(".") ? candidate.substring(0, candidate.length() - 1) : candidate;
        return NUMERIC_LABEL.matcher(name.substring(name.lastIndexOf('.') + 1)).matches();
    }

    private static boolean isHostName(String candidate) {
        String name = candidate.endsWith(".") ? candidate.substring(0, candidate.length() - 1) : candidate;
        if (name.isEmpty()) return false;
        for (String label : name.split("\\.", -1)) {
            if (label.length() > MAX_LABEL_LENGTH || !LABEL.matcher(label).matches()) return false;
        }
        return true;
    }

    private static IllegalArgumentException invalidHost(String raw, String problem) {
        return new IllegalArgumentException("'" + raw.trim() + "' " + problem);
    }
}
