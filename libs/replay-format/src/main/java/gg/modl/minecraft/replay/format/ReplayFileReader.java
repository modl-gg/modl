package gg.modl.minecraft.replay.format;

import com.google.protobuf.CodedInputStream;
import gg.modl.proto.modl.v1.ReplayFrame;
import org.jspecify.annotations.Nullable;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

public final class ReplayFileReader implements Closeable {
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final int NO_FRAME = -1;

    private final InputStream decoded;
    private final CodedInputStream frames;

    private ReplayFileReader(InputStream decoded) {
        this.decoded = decoded;
        this.frames = CodedInputStream.newInstance(decoded, BUFFER_BYTES);
    }

    public static ReplayFileReader open(InputStream source, long maxUncompressedBytes) throws IOException {
        InputStream raw = new SourceInputStream(source);
        try {
            byte[] prefix = new byte[ReplayFileFormat.prefixLength()];
            new DataInputStream(raw).readFully(prefix);
            if (!ReplayFileFormat.hasMagic(prefix)) throw new ReplayFormatException("Not a modl replay file");
            int version = ((prefix[4] & 0xFF) << 8) | (prefix[5] & 0xFF);
            if (version != ReplayFileFormat.FORMAT_VERSION) {
                throw new ReplayFormatException("Unsupported replay format version " + version);
            }
            return new ReplayFileReader(new BoundedInputStream(new GZIPInputStream(raw, BUFFER_BYTES), maxUncompressedBytes));
        } catch (IOException e) {
            throw classified(e);
        }
    }

    public @Nullable ReplayFrame next() throws IOException {
        try {
            int length = nextFrameLength();
            if (length == NO_FRAME) return null;
            return ReplayFrame.parseFrom(frames.readRawBytes(length));
        } catch (IOException e) {
            throw classified(e);
        }
    }

    public boolean scanNext(ReplayFrameVisitor visitor) throws IOException {
        try {
            int length = nextFrameLength();
            if (length == NO_FRAME) return false;
            int outer = frames.pushLimit(length);
            visitor.visit(frames);
            frames.skipRawBytes(frames.getBytesUntilLimit());
            frames.popLimit(outer);
            return true;
        } catch (IOException e) {
            throw classified(e);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            decoded.close();
        } catch (IOException e) {
            throw classified(e);
        }
    }

    private int nextFrameLength() throws IOException {
        frames.resetSizeCounter();
        if (frames.isAtEnd()) return NO_FRAME;
        int length = frames.readRawVarint32();
        if (length < 0 || length > ReplayLimits.MAX_FRAME_BYTES) {
            throw new ReplayFormatException("Replay frame length " + length + " is out of range");
        }
        return length;
    }

    private static IOException classified(IOException failure) {
        if (failure instanceof SourceReadException) return ((SourceReadException) failure).failure();
        if (failure instanceof ReplayFormatException) return failure;
        if (failure instanceof EOFException) return new ReplayFormatException("Replay file is truncated", failure);
        return new ReplayFormatException("Replay file is malformed: " + failure.getMessage(), failure);
    }
}
