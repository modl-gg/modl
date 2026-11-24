package gg.modl.backend.hostedimport.archive;

import gg.modl.backend.hostedimport.model.ImportFailure;
import gg.modl.backend.hostedimport.model.ImportReason;
import org.jspecify.annotations.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Map;

public final class NdjsonLines {
    private static final int INITIAL_LINE = 64 * 1024;
    private static final int READ_CHUNK = 64 * 1024;
    private static final byte NEWLINE = '\n';
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final InputStream input;
    private final int maxLineBytes;
    private final byte[] chunk = new byte[READ_CHUNK];
    private int chunkLength;
    private int chunkPosition;
    private byte[] line = new byte[INITIAL_LINE];
    private int lineLength;
    private long lineNumber;
    private boolean ended;
    private boolean oversized;

    public NdjsonLines(InputStream input) {
        this(input, ArchiveLimits.MAX_LINE_BYTES);
    }

    NdjsonLines(InputStream input, int maxLineBytes) {
        this.input = input;
        this.maxLineBytes = maxLineBytes;
    }

    public boolean next() {
        if (ended) {
            return false;
        }
        lineLength = 0;
        oversized = false;
        boolean sawAny = false;
        while (true) {
            if (chunkPosition == chunkLength && !fill()) {
                ended = true;
                if (sawAny) {
                    throw new ImportFailure(ImportReason.ARCHIVE_DAMAGED);
                }
                return false;
            }
            byte value = chunk[chunkPosition++];
            if (value == NEWLINE) {
                if (!sawAny) {
                    throw new ImportFailure(ImportReason.ARCHIVE_DAMAGED);
                }
                lineNumber++;
                if (lineNumber == 1 && startsWithBom()) {
                    throw new ImportFailure(ImportReason.ARCHIVE_DAMAGED);
                }
                return true;
            }
            sawAny = true;
            append(value);
        }
    }

    public long lineNumber() {
        return lineNumber;
    }

    public int lineBytes() {
        return lineLength;
    }

    public boolean isOversized() {
        return oversized;
    }

    public Map<String, @Nullable Object> decode() {
        if (oversized) {
            throw new UnreadableDocumentException("line too long", true);
        }
        return ExtendedJsonDecoder.decodeLine(line, lineLength);
    }

    public void skipTo(long targetLine) {
        while (lineNumber < targetLine) {
            if (!next()) {
                throw new ImportFailure(ImportReason.ARCHIVE_MISSING);
            }
        }
    }

    private boolean startsWithBom() {
        return lineLength >= BOM.length && Arrays.equals(line, 0, BOM.length, BOM, 0, BOM.length);
    }

    private void append(byte value) {
        if (oversized) {
            return;
        }
        if (lineLength == maxLineBytes) {
            oversized = true;
            return;
        }
        if (lineLength == line.length) {
            line = Arrays.copyOf(line, (int) Math.min((long) line.length * 2, maxLineBytes));
        }
        line[lineLength++] = value;
    }

    private boolean fill() {
        try {
            int count = input.read(chunk, 0, chunk.length);
            if (count <= 0) {
                return false;
            }
            chunkLength = count;
            chunkPosition = 0;
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
