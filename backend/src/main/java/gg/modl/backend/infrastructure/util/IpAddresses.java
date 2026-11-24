package gg.modl.backend.infrastructure.util;

import gg.modl.address.IpLiteral;
import org.jspecify.annotations.Nullable;

public final class IpAddresses {
    private static final int LONGEST_ADDRESS = 45;

    private IpAddresses() {
    }

    public static String withoutZone(String address) {
        return IpLiteral.withoutZone(address);
    }

    @Nullable
    public static String withoutZoneIfStorable(@Nullable String address) {
        if (address == null) {
            return null;
        }
        String withoutZone = withoutZone(address.trim());
        return withoutZone.length() > LONGEST_ADDRESS ? null : withoutZone;
    }

    @Nullable
    public static String literalOrNull(@Nullable String address) {
        if (address == null) {
            return null;
        }
        String trimmed = address.trim();
        return isLiteral(trimmed) ? trimmed : null;
    }

    public static boolean isLiteral(String s) {
        return IpLiteral.isLiteral(s);
    }
}
