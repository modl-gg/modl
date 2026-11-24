package gg.modl.address;

import org.jspecify.annotations.Nullable;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

public final class IpLiteral {
    private static final int IPV4_OCTETS = 4;
    private static final int IPV6_GROUPS = 8;
    private static final int IPV4_GROUPS_IN_IPV6 = 2;
    private static final int LONGEST_HEXTET = 4;
    private static final int LONGEST_OCTET = 3;
    private static final int MAX_OCTET = 255;
    private static final char IPV6_ZONE_SEPARATOR = '%';
    private static final String IPV6_COMPRESSION = "::";
    private static final String ZONE_ID_PUNCTUATION = "._~-";
    private static final int IPV6_BYTES = 16;
    private static final int IPV4_MAPPED_PREFIX_BYTES = 10;
    private static final int BYTE_MASK = 0xff;
    private static final int BITS_PER_BYTE = 8;
    private static final String IPV4_MAPPED_PREFIX = "::ffff:";

    private IpLiteral() {
    }

    public static String withoutZone(String address) {
        int zone = address.indexOf(IPV6_ZONE_SEPARATOR);
        return zone < 0 ? address : address.substring(0, zone);
    }

    public static boolean isLiteral(String value) {
        return value.indexOf(':') >= 0 ? isIpv6Literal(value) : isIpv4Literal(value);
    }

    public static boolean isIpv4Literal(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != IPV4_OCTETS) {
            return false;
        }
        for (String octet : octets) {
            if (!isOctet(octet)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isIpv6Literal(String value) {
        int zone = value.indexOf(IPV6_ZONE_SEPARATOR);
        if (zone >= 0 && !isZoneId(value.substring(zone + 1))) {
            return false;
        }
        return isIpv6(withoutZone(value));
    }

    public static String display(String address) {
        if (!isIpv6Literal(address)) {
            return address;
        }
        InetAddress parsed = parseOrNull(address);
        if (parsed == null) {
            return address;
        }
        int zone = address.indexOf(IPV6_ZONE_SEPARATOR);
        return compressedIpv6(ipv6Bytes(parsed)) + (zone < 0 ? "" : address.substring(zone));
    }

    public static @Nullable InetAddress parseOrNull(@Nullable String literal) {
        if (literal == null) {
            return null;
        }
        String trimmed = literal.trim();
        if (!isLiteral(trimmed)) {
            return null;
        }
        try {
            return InetAddress.getByName(withoutZone(trimmed));
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static byte[] ipv6Bytes(InetAddress address) {
        byte[] raw = address.getAddress();
        if (!(address instanceof Inet4Address)) {
            return raw;
        }
        byte[] mapped = new byte[IPV6_BYTES];
        mapped[IPV4_MAPPED_PREFIX_BYTES] = (byte) BYTE_MASK;
        mapped[IPV4_MAPPED_PREFIX_BYTES + 1] = (byte) BYTE_MASK;
        System.arraycopy(raw, 0, mapped, IPV6_BYTES - IPV4_OCTETS, IPV4_OCTETS);
        return mapped;
    }

    private static String compressedIpv6(byte[] bytes) {
        if (isIpv4Mapped(bytes)) {
            return IPV4_MAPPED_PREFIX + dotted(bytes, IPV6_BYTES - IPV4_OCTETS);
        }
        int[] groups = new int[IPV6_GROUPS];
        for (int i = 0; i < IPV6_GROUPS; i++) {
            groups[i] = ((bytes[2 * i] & BYTE_MASK) << BITS_PER_BYTE) | (bytes[2 * i + 1] & BYTE_MASK);
        }
        int[] zeros = longestZeroRun(groups);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < IPV6_GROUPS; i++) {
            if (i == zeros[0]) {
                text.append(IPV6_COMPRESSION);
                i += zeros[1] - 1;
                continue;
            }
            if (text.length() > 0 && text.charAt(text.length() - 1) != ':') {
                text.append(':');
            }
            text.append(Integer.toHexString(groups[i]));
        }
        return text.toString();
    }

    private static int[] longestZeroRun(int[] groups) {
        int bestStart = -1;
        int bestLength = 1;
        int runStart = -1;
        for (int i = 0; i <= groups.length; i++) {
            if (i < groups.length && groups[i] == 0) {
                runStart = runStart < 0 ? i : runStart;
            } else if (runStart >= 0) {
                if (i - runStart > bestLength) {
                    bestStart = runStart;
                    bestLength = i - runStart;
                }
                runStart = -1;
            }
        }
        return new int[]{bestStart, bestLength};
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < IPV4_MAPPED_PREFIX_BYTES; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return (bytes[IPV4_MAPPED_PREFIX_BYTES] & BYTE_MASK) == BYTE_MASK
            && (bytes[IPV4_MAPPED_PREFIX_BYTES + 1] & BYTE_MASK) == BYTE_MASK;
    }

    private static String dotted(byte[] bytes, int offset) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < IPV4_OCTETS; i++) {
            if (i > 0) {
                text.append('.');
            }
            text.append(bytes[offset + i] & BYTE_MASK);
        }
        return text.toString();
    }

    private static boolean isZoneId(String zone) {
        if (zone.isEmpty()) {
            return false;
        }
        for (int i = 0; i < zone.length(); i++) {
            if (ZONE_ID_PUNCTUATION.indexOf(zone.charAt(i)) < 0 && !isAsciiLetterOrDigit(zone.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiLetterOrDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isOctet(String octet) {
        int length = octet.length();
        if (length < 1 || length > LONGEST_OCTET) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            char c = octet.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        if (length > 1 && octet.charAt(0) == '0') {
            return false;
        }
        return Integer.parseInt(octet) <= MAX_OCTET;
    }

    private static boolean isIpv6(String value) {
        int compression = value.indexOf(IPV6_COMPRESSION);
        boolean compressed = compression >= 0;
        if (compressed && value.indexOf(IPV6_COMPRESSION, compression + 1) >= 0) {
            return false;
        }
        String[] sides = compressed
            ? new String[]{value.substring(0, compression), value.substring(compression + IPV6_COMPRESSION.length())}
            : new String[]{value};
        int groupCount = 0;
        for (int side = 0; side < sides.length; side++) {
            if (sides[side].isEmpty()) {
                continue;
            }
            String[] groups = sides[side].split(":", -1);
            for (int i = 0; i < groups.length; i++) {
                boolean lastGroup = side == sides.length - 1 && i == groups.length - 1;
                int width = groupWidth(groups[i], lastGroup);
                if (width == 0) {
                    return false;
                }
                groupCount += width;
            }
        }
        return compressed ? groupCount < IPV6_GROUPS : groupCount == IPV6_GROUPS;
    }

    private static int groupWidth(String group, boolean lastGroup) {
        if (group.indexOf('.') >= 0) {
            return lastGroup && isIpv4Literal(group) ? IPV4_GROUPS_IN_IPV6 : 0;
        }
        return isHextet(group) ? 1 : 0;
    }

    private static boolean isHextet(String group) {
        int length = group.length();
        if (length < 1 || length > LONGEST_HEXTET) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            char c = group.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
