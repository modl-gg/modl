package gg.modl.minecraft.replay.recording;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

final class RecordWriter {
    static final int MAX_STRING_BYTES = 8192;
    private static final int INITIAL_CAPACITY = 64;

    private byte[] bytes = new byte[INITIAL_CAPACITY];
    private int length;

    RecordWriter begin(int kind, long timeMs) {
        length = 0;
        writeByte(kind);
        writeVarLong(timeMs);
        return this;
    }

    byte[] bytes() {
        return bytes;
    }

    int length() {
        return length;
    }

    RecordWriter writeByte(int value) {
        ensure(1);
        bytes[length++] = (byte) value;
        return this;
    }

    RecordWriter writeBoolean(boolean value) {
        return writeByte(value ? 1 : 0);
    }

    RecordWriter writeVarInt(int value) {
        ensure(5);
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            bytes[length++] = (byte) ((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        bytes[length++] = (byte) remaining;
        return this;
    }

    RecordWriter writeSignedVarInt(int value) {
        return writeVarInt((value << 1) ^ (value >> 31));
    }

    RecordWriter writeVarLong(long value) {
        ensure(10);
        long remaining = value;
        while ((remaining & ~0x7FL) != 0) {
            bytes[length++] = (byte) ((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        bytes[length++] = (byte) remaining;
        return this;
    }

    RecordWriter writeSignedVarLong(long value) {
        return writeVarLong((value << 1) ^ (value >> 63));
    }

    RecordWriter writeFloat(float value) {
        int bits = Float.floatToIntBits(value);
        ensure(4);
        bytes[length++] = (byte) (bits >>> 24);
        bytes[length++] = (byte) (bits >>> 16);
        bytes[length++] = (byte) (bits >>> 8);
        bytes[length++] = (byte) bits;
        return this;
    }

    RecordWriter writeLong(long value) {
        ensure(8);
        for (int shift = 56; shift >= 0; shift -= 8) {
            bytes[length++] = (byte) (value >>> shift);
        }
        return this;
    }

    RecordWriter writeUuid(UUID uuid) {
        writeLong(uuid.getMostSignificantBits());
        return writeLong(uuid.getLeastSignificantBits());
    }

    static boolean fits(String value) {
        return value.length() * 3 <= MAX_STRING_BYTES || value.getBytes(StandardCharsets.UTF_8).length <= MAX_STRING_BYTES;
    }

    RecordWriter writeString(String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        int size = Math.min(encoded.length, MAX_STRING_BYTES);
        writeVarInt(size);
        ensure(size);
        System.arraycopy(encoded, 0, bytes, length, size);
        length += size;
        return this;
    }

    private void ensure(int extra) {
        if (length + extra <= bytes.length) return;
        bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, length + extra));
    }
}
