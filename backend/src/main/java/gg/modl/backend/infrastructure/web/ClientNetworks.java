package gg.modl.backend.infrastructure.web;

import gg.modl.address.IpLiteral;
import org.jspecify.annotations.Nullable;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.HexFormat;

public final class ClientNetworks {
    private static final int IPV6_NETWORK_BYTES = 8;
    private static final String IPV6_NETWORK_SUFFIX = "::/64";
    private static final String UNKNOWN = "unknown";

    private ClientNetworks() {
    }

    public static String keyOf(@Nullable String address) {
        if (address == null || address.isBlank()) {
            return UNKNOWN;
        }
        InetAddress parsed = IpLiteral.parseOrNull(address);
        if (parsed == null) {
            return address;
        }
        if (parsed instanceof Inet6Address) {
            byte[] network = new byte[IPV6_NETWORK_BYTES];
            System.arraycopy(parsed.getAddress(), 0, network, 0, IPV6_NETWORK_BYTES);
            return HexFormat.of().formatHex(network) + IPV6_NETWORK_SUFFIX;
        }
        return parsed.getHostAddress();
    }
}
