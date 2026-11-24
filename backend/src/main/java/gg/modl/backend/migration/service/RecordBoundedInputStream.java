package gg.modl.backend.migration.service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

public final class RecordBoundedInputStream extends FilterInputStream {
    public static final long MAX_RECORD_BYTES = 16L * 1024 * 1024;
    public static final long MAX_RECORD_VALUES = 1_000_000;

    private final long maxRecordBytes;
    private final long maxRecordValues;
    private long position;
    private long recordBytes;
    private long recordValues;
    private boolean inRecord;
    private boolean limitExceeded;

    public RecordBoundedInputStream(InputStream source) {
        this(source, MAX_RECORD_BYTES, MAX_RECORD_VALUES);
    }

    RecordBoundedInputStream(InputStream source, long maxRecordBytes, long maxRecordValues) {
        super(source);
        this.maxRecordBytes = maxRecordBytes;
        this.maxRecordValues = maxRecordValues;
    }

    public void startRecord() {
        inRecord = true;
        recordBytes = 0;
        recordValues = 0;
    }

    public void endRecord() {
        inRecord = false;
    }

    public long position() {
        return position;
    }

    public boolean limitExceeded() {
        return limitExceeded;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value >= 0) {
            position++;
            if (inRecord) {
                count(value);
                requireWithinLimits();
            }
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int read = super.read(buffer, offset, length);
        if (read > 0) {
            position += read;
            if (inRecord) {
                for (int index = offset; index < offset + read; index++) {
                    count(buffer[index]);
                }
                requireWithinLimits();
            }
        }
        return read;
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    private void count(int value) {
        recordBytes++;
        if (value == '{' || value == '[' || value == ',') {
            recordValues++;
        }
    }

    private void requireWithinLimits() throws IOException {
        if (recordBytes > maxRecordBytes || recordValues > maxRecordValues) {
            limitExceeded = true;
            throw new IOException("A player record in the import file is too large");
        }
    }
}
