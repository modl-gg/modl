package gg.modl.minecraft.replay.format;

import java.io.IOException;
import java.io.InputStream;

final class BoundedInputStream extends InputStream {
    private final InputStream source;
    private final long limit;
    private long remaining;

    BoundedInputStream(InputStream source, long limit) {
        this.source = source;
        this.limit = limit;
        this.remaining = limit;
    }

    @Override
    public int read() throws IOException {
        if (remaining <= 0) return endOrOverflow();
        int value = source.read();
        if (value >= 0) remaining--;
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (remaining <= 0) return endOrOverflow();
        int read = source.read(buffer, offset, (int) Math.min(length, remaining));
        if (read > 0) remaining -= read;
        return read;
    }

    @Override
    public void close() throws IOException {
        source.close();
    }

    private int endOrOverflow() throws IOException {
        if (source.read() < 0) return -1;
        throw new ReplayFormatException("Replay exceeds the uncompressed size limit of " + limit + " bytes");
    }
}
