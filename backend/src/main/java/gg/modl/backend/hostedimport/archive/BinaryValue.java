package gg.modl.backend.hostedimport.archive;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

public final class BinaryValue {
    private static final int LEGACY_UUID = 3;
    private static final int STANDARD_UUID = 4;
    private static final int UUID_BYTES = 16;

    private final int subtype;
    private final byte[] data;

    public BinaryValue(int subtype, byte[] data) {
        this.subtype = subtype;
        this.data = data.clone();
    }

    public Optional<UUID> asUuid() {
        if (data.length != UUID_BYTES || (subtype != LEGACY_UUID && subtype != STANDARD_UUID)) {
            return Optional.empty();
        }
        byte[] ordered = subtype == LEGACY_UUID ? javaLegacyOrder(data) : data;
        ByteBuffer buffer = ByteBuffer.wrap(ordered);
        return Optional.of(new UUID(buffer.getLong(), buffer.getLong()));
    }

    public String plainText() {
        return asUuid().map(UUID::toString).orElseGet(() -> Base64.getEncoder().encodeToString(data));
    }

    private static byte[] javaLegacyOrder(byte[] bytes) {
        byte[] reversed = new byte[UUID_BYTES];
        for (int i = 0; i < 8; i++) {
            reversed[i] = bytes[7 - i];
            reversed[8 + i] = bytes[15 - i];
        }
        return reversed;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BinaryValue value && value.subtype == subtype && Arrays.equals(value.data, data);
    }

    @Override
    public int hashCode() {
        return 31 * subtype + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "BinaryValue[" + subtype + "," + data.length + "]";
    }
}
