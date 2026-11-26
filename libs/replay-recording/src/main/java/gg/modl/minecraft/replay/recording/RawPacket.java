package gg.modl.minecraft.replay.recording;

import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

final class RawPacket {
    static final int CHUNK_DATA = 1;
    static final int LEGACY_CHUNK = 2;
    static final int LEGACY_BULK = 3;
    static final int LIGHT_UPDATE = 4;

    private final int kind;
    private final byte[] compressed;
    private final int rawLength;
    private int references;

    private RawPacket(int kind, byte[] compressed, int rawLength) {
        this.kind = kind;
        this.compressed = compressed;
        this.rawLength = rawLength;
    }

    static RawPacket compress(int kind, byte[] raw, DeflaterPool deflaters) {
        return new RawPacket(kind, deflaters.deflate(raw), raw.length);
    }

    byte[] inflate() throws DataFormatException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            byte[] raw = new byte[rawLength];
            int offset = 0;
            while (offset < rawLength && !inflater.finished()) {
                int n = inflater.inflate(raw, offset, rawLength - offset);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                offset += n;
            }
            if (offset != rawLength) throw new DataFormatException("Stored packet is truncated");
            return raw;
        } finally {
            inflater.end();
        }
    }

    int kind() {
        return kind;
    }

    long retainedBytes() {
        return compressed.length + 32L;
    }

    synchronized boolean retain() {
        return references++ == 0;
    }

    synchronized boolean release() {
        return --references == 0;
    }
}
