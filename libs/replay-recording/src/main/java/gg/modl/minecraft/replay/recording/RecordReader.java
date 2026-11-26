package gg.modl.minecraft.replay.recording;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class RecordReader {
    private final byte[] bytes;
    private int position;
    private final int end;

    RecordReader(byte[] bytes, int offset, int end) {
        this.bytes = bytes;
        this.position = offset;
        this.end = end;
    }

    boolean hasMore() {
        return position < end;
    }

    int readByte() {
        return bytes[position++] & 0xFF;
    }

    boolean readBoolean() {
        return readByte() != 0;
    }

    int readVarInt() {
        int value = 0;
        int shift = 0;
        while (true) {
            int b = readByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
        }
    }

    int readSignedVarInt() {
        int raw = readVarInt();
        return (raw >>> 1) ^ -(raw & 1);
    }

    long readVarLong() {
        long value = 0;
        int shift = 0;
        while (true) {
            int b = readByte();
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
        }
    }

    long readSignedVarLong() {
        long raw = readVarLong();
        return (raw >>> 1) ^ -(raw & 1);
    }

    float readFloat() {
        int bits = (readByte() << 24) | (readByte() << 16) | (readByte() << 8) | readByte();
        return Float.intBitsToFloat(bits);
    }

    long readLong() {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | readByte();
        }
        return value;
    }

    UUID readUuid() {
        long most = readLong();
        return new UUID(most, readLong());
    }

    String readString() {
        int size = readVarInt();
        String value = new String(bytes, position, size, StandardCharsets.UTF_8);
        position += size;
        return value;
    }
}
