package gg.modl.minecraft.replay.recording;

final class VarIntCursor {
    private final byte[] bytes;
    private int position;

    VarIntCursor(byte[] bytes) {
        this.bytes = bytes;
    }

    VarIntCursor(byte[] bytes, int position) {
        this.bytes = bytes;
        this.position = position;
    }

    int next() {
        int value = 0;
        int shift = 0;
        while (position < bytes.length && shift < 35) {
            int b = bytes[position++] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
        }
        throw new IllegalStateException("Malformed varint in stored packet");
    }

    int position() {
        return position;
    }
}
