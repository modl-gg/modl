package gg.modl.address;

import org.jspecify.annotations.Nullable;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

public enum AddressScope {
    UNSPECIFIED,
    LOOPBACK,
    LINK_LOCAL,
    PRIVATE,
    CARRIER_GRADE_NAT,
    UNIQUE_LOCAL,
    RESERVED,
    PUBLIC;

    private static final int CGNAT_FIRST_OCTET = 100;
    private static final int CGNAT_SECOND_OCTET_MASK = 0xC0;
    private static final int CGNAT_SECOND_OCTET_PREFIX = 0x40;
    private static final int THIS_NETWORK_FIRST_OCTET = 0;
    private static final int FUTURE_USE_FIRST_OCTET = 240;
    private static final int IETF_PROTOCOL_FIRST_OCTET = 192;
    private static final int BENCHMARK_FIRST_OCTET = 198;
    private static final int BENCHMARK_SECOND_OCTET_MASK = 0xFE;
    private static final int BENCHMARK_SECOND_OCTET = 18;
    private static final int UNIQUE_LOCAL_MASK = 0xFE;
    private static final int UNIQUE_LOCAL_PREFIX = 0xFC;
    private static final int SIX_TO_FOUR_FIRST = 0x20;
    private static final int SIX_TO_FOUR_SECOND = 0x02;
    private static final int NAT64_PREFIX_LENGTH = 12;
    private static final byte[] NAT64_PREFIX = {0x00, 0x64, (byte) 0xFF, (byte) 0x9B, 0, 0, 0, 0, 0, 0, 0, 0};

    public static AddressScope classify(InetAddress address) {
        if (address instanceof Inet6Address) {
            return classifyV6((Inet6Address) address);
        }
        return classifyV4(address);
    }

    public static Optional<AddressScope> ofLiteral(String literal) {
        InetAddress address = IpLiteral.parseOrNull(literal);
        return address == null ? Optional.<AddressScope>empty() : Optional.of(classify(address));
    }

    public static boolean isPublicLiteral(String literal) {
        return ofLiteral(literal).map(AddressScope::isPublic).orElse(false);
    }

    public boolean isPublic() {
        return this == PUBLIC;
    }

    private static AddressScope classifyV4(InetAddress address) {
        if (address.isAnyLocalAddress()) {
            return UNSPECIFIED;
        }
        if (address.isLoopbackAddress()) {
            return LOOPBACK;
        }
        if (address.isLinkLocalAddress()) {
            return LINK_LOCAL;
        }
        if (address.isSiteLocalAddress()) {
            return PRIVATE;
        }
        byte[] octets = address.getAddress();
        int first = octets[0] & 0xFF;
        int second = octets[1] & 0xFF;
        if (first == CGNAT_FIRST_OCTET && (second & CGNAT_SECOND_OCTET_MASK) == CGNAT_SECOND_OCTET_PREFIX) {
            return CARRIER_GRADE_NAT;
        }
        if (address.isMulticastAddress() || first == THIS_NETWORK_FIRST_OCTET || first >= FUTURE_USE_FIRST_OCTET
            || (first == IETF_PROTOCOL_FIRST_OCTET && second == 0 && (octets[2] & 0xFF) == 0)
            || (first == BENCHMARK_FIRST_OCTET && (second & BENCHMARK_SECOND_OCTET_MASK) == BENCHMARK_SECOND_OCTET)) {
            return RESERVED;
        }
        return PUBLIC;
    }

    private static AddressScope classifyV6(Inet6Address address) {
        byte[] bytes = address.getAddress();
        if (address.isAnyLocalAddress()) {
            return UNSPECIFIED;
        }
        if (address.isLoopbackAddress()) {
            return LOOPBACK;
        }
        if (address.isLinkLocalAddress()) {
            return LINK_LOCAL;
        }
        if ((bytes[0] & UNIQUE_LOCAL_MASK) == UNIQUE_LOCAL_PREFIX || address.isSiteLocalAddress()) {
            return UNIQUE_LOCAL;
        }
        if (address.isMulticastAddress()) {
            return RESERVED;
        }
        Inet4Address embedded = embeddedIpv4(bytes);
        return embedded != null ? classifyV4(embedded) : PUBLIC;
    }

    private static @Nullable Inet4Address embeddedIpv4(byte[] bytes) {
        if ((bytes[0] & 0xFF) == SIX_TO_FOUR_FIRST && (bytes[1] & 0xFF) == SIX_TO_FOUR_SECOND) {
            return v4(bytes[2], bytes[3], bytes[4], bytes[5]);
        }
        for (int index = 0; index < NAT64_PREFIX_LENGTH; index++) {
            if (bytes[index] != NAT64_PREFIX[index]) {
                return null;
            }
        }
        return v4(bytes[12], bytes[13], bytes[14], bytes[15]);
    }

    private static @Nullable Inet4Address v4(byte a, byte b, byte c, byte d) {
        try {
            return (Inet4Address) InetAddress.getByAddress(new byte[] {a, b, c, d});
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
