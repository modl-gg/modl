package gg.modl.minecraft.core.imports.support;

import gg.modl.address.IpLiteral;
import org.jspecify.annotations.Nullable;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

public final class PlayerKeys {
    private static final Pattern DASHED = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern UNDASHED = Pattern.compile("[0-9a-fA-F]{32}");
    private static final int UUID_BYTES = 16;
    private static final String OFFLINE_PREFIX = "OfflinePlayer:";

    private PlayerKeys() {
    }

    public static @Nullable String uuidOrNull(@Nullable String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (DASHED.matcher(trimmed).matches()) return trimmed.toLowerCase(Locale.ROOT);
        if (!UNDASHED.matcher(trimmed).matches()) return null;
        String hex = trimmed.toLowerCase(Locale.ROOT);
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-"
                + hex.substring(16, 20) + "-" + hex.substring(20);
    }

    public static @Nullable String uuidOrNull(byte @Nullable [] raw) {
        if (raw == null || raw.length != UUID_BYTES) return null;
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        return new UUID(buffer.getLong(), buffer.getLong()).toString();
    }

    public static byte[] bytesOf(String uuid) {
        UUID parsed = UUID.fromString(uuid);
        return ByteBuffer.allocate(UUID_BYTES)
                .putLong(parsed.getMostSignificantBits())
                .putLong(parsed.getLeastSignificantBits())
                .array();
    }

    public static String offlineUuid(String name) {
        return UUID.nameUUIDFromBytes((OFFLINE_PREFIX + name).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static byte @Nullable [] bytesOfAddress(@Nullable String address) {
        InetAddress parsed = IpLiteral.parseOrNull(address);
        return parsed == null ? null : parsed.getAddress();
    }

    public static boolean isAddressLiteral(@Nullable String value) {
        return value != null && IpLiteral.isLiteral(value.trim());
    }

    public static @Nullable String addressOrNull(@Nullable String literal) {
        InetAddress parsed = IpLiteral.parseOrNull(literal == null ? null : literal.trim());
        return parsed == null ? null : parsed.getHostAddress();
    }

    public static @Nullable String addressOrNull(byte @Nullable [] raw) {
        if (raw == null || (raw.length != 4 && raw.length != UUID_BYTES)) return null;
        try {
            return InetAddress.getByAddress(raw).getHostAddress();
        } catch (UnknownHostException invalid) {
            return null;
        }
    }
}
