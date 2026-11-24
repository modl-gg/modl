package gg.modl.backend.backup;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class VerifiedEntryStream extends FilterInputStream {
    private static final long UNKNOWN_CRC = -1L;

    private final ZipEntry entry;
    private final CheckedInputStream checked;
    private final DeclaredSizeInput declared;

    private VerifiedEntryStream(ZipEntry entry, DeclaredSizeInput declared) {
        this(entry, declared, new CheckedInputStream(declared, new CRC32()));
    }

    private VerifiedEntryStream(ZipEntry entry, DeclaredSizeInput declared, CheckedInputStream checked) {
        super(checked);
        this.entry = entry;
        this.declared = declared;
        this.checked = checked;
    }

    public static VerifiedEntryStream open(ZipFile archive, ZipEntry entry) throws IOException {
        return openAtMost(archive, entry, Long.MAX_VALUE);
    }

    public static VerifiedEntryStream openAtMost(ZipFile archive, ZipEntry entry, long maxBytes) throws IOException {
        long size = entry.getSize();
        if (size < 0 || size > maxBytes) {
            throw new IOException("Backup is damaged: " + entry.getName() + " has no size or is larger than " + maxBytes + " bytes");
        }
        return new VerifiedEntryStream(entry, new DeclaredSizeInput(archive.getInputStream(entry), entry.getName(), size));
    }

    public void requireIntact() throws IOException {
        checked.transferTo(OutputStream.nullOutputStream());
        declared.requireComplete();
        if (entry.getCrc() != UNKNOWN_CRC && checked.getChecksum().getValue() != entry.getCrc()) {
            throw new IOException("Backup is damaged: " + entry.getName() + " does not match its checksum");
        }
    }

    private static final class DeclaredSizeInput extends FilterInputStream {
        private final String name;
        private final long size;
        private long read;

        private DeclaredSizeInput(InputStream input, String name, long size) {
            super(input);
            this.name = name;
            this.size = size;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) {
                count(count);
            }
            return count;
        }

        @Override
        public long skip(long count) throws IOException {
            long skipped = super.skip(count);
            count(skipped);
            return skipped;
        }

        private void count(long bytes) throws IOException {
            read += bytes;
            if (read > size) {
                throw new IOException("Backup is damaged: " + name + " holds more than its declared " + size + " bytes");
            }
        }

        private void requireComplete() throws IOException {
            if (read != size) {
                throw new IOException("Backup is damaged: " + name + " holds " + read + " bytes, it declares " + size);
            }
        }
    }
}
