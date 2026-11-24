package gg.modl.backend.iplookup.provider;

import gg.modl.backend.infrastructure.util.IpAddresses;

final class LookupAddress {
    private LookupAddress() {
    }

    static String require(String ip) {
        String trimmed = ip == null ? "" : ip.trim();
        if (!IpAddresses.isLiteral(trimmed) || trimmed.indexOf('%') >= 0) {
            throw new IllegalArgumentException("Not an IP address literal");
        }
        return trimmed;
    }
}
