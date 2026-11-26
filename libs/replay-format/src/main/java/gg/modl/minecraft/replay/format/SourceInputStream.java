package gg.modl.minecraft.replay.format;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

final class SourceInputStream extends FilterInputStream {
    SourceInputStream(InputStream source) {
        super(source);
    }

    @Override
    public int read() throws IOException {
        try {
            return in.read();
        } catch (IOException e) {
            throw new SourceReadException(e);
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        try {
            return in.read(buffer, offset, length);
        } catch (IOException e) {
            throw new SourceReadException(e);
        }
    }

    @Override
    public long skip(long count) throws IOException {
        try {
            return in.skip(count);
        } catch (IOException e) {
            throw new SourceReadException(e);
        }
    }

    @Override
    public int available() throws IOException {
        try {
            return in.available();
        } catch (IOException e) {
            throw new SourceReadException(e);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            in.close();
        } catch (IOException e) {
            throw new SourceReadException(e);
        }
    }
}
