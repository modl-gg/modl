package gg.modl.minecraft.replay.format;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

final class SingleMemberGzipInputStream extends InflaterInputStream {
    private static final int HEADER_BYTES = 10;
    private static final int GZIP_MAGIC_FIRST = 0x1f;
    private static final int GZIP_MAGIC_SECOND = 0x8b;
    private static final int DEFLATE = 8;

    private final PushbackInputStream source;
    private final CRC32 checksum = new CRC32();
    private boolean ended;

    private SingleMemberGzipInputStream(PushbackInputStream source, int bufferBytes) {
        super(source, new Inflater(true), bufferBytes);
        this.source = source;
    }

    static SingleMemberGzipInputStream open(InputStream raw, int bufferBytes) throws IOException {
        PushbackInputStream source = new PushbackInputStream(raw, bufferBytes);
        requireHeader(source);
        return new SingleMemberGzipInputStream(source, bufferBytes);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (ended) return -1;
        int read = super.read(buffer, offset, length);
        if (read == -1) {
            ended = true;
            requireTrailerAndEnd();
            return -1;
        }
        checksum.update(buffer, offset, read);
        return read;
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            inf.end();
        }
    }

    private void requireTrailerAndEnd() throws IOException {
        int remaining = inf.getRemaining();
        if (remaining > 0) source.unread(buf, len - remaining, remaining);
        DataInputStream trailer = new DataInputStream(source);
        long storedChecksum = Integer.toUnsignedLong(Integer.reverseBytes(trailer.readInt()));
        long storedSize = Integer.toUnsignedLong(Integer.reverseBytes(trailer.readInt()));
        if (storedChecksum != checksum.getValue() || storedSize != (inf.getBytesWritten() & 0xffffffffL)) {
            throw new ReplayFormatException("Replay file has a corrupt compression trailer");
        }
        if (source.read() != -1) throw new ReplayFormatException("Replay file has data after its compressed frames");
    }

    private static void requireHeader(InputStream source) throws IOException {
        byte[] header = new byte[HEADER_BYTES];
        new DataInputStream(source).readFully(header);
        if ((header[0] & 0xFF) != GZIP_MAGIC_FIRST || (header[1] & 0xFF) != GZIP_MAGIC_SECOND || header[2] != DEFLATE) {
            throw new ReplayFormatException("Replay file is not gzip compressed");
        }
        if (header[3] != 0) throw new ReplayFormatException("Replay file uses gzip header fields modl does not write");
    }
}
