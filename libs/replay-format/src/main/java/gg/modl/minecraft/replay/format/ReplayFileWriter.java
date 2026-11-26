package gg.modl.minecraft.replay.format;

import gg.modl.proto.modl.v1.ReplayFrame;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;

public final class ReplayFileWriter implements Closeable {
    private static final int BUFFER_BYTES = 64 * 1024;

    private final OutputStream compressed;

    public ReplayFileWriter(OutputStream target) throws IOException {
        target.write(ReplayFileFormat.magic());
        target.write(ReplayFileFormat.versionBytes());
        this.compressed = new BufferedOutputStream(new GZIPOutputStream(target, BUFFER_BYTES), BUFFER_BYTES);
    }

    public void write(ReplayFrame frame) throws IOException {
        if (frame.getSerializedSize() > ReplayLimits.MAX_FRAME_BYTES) {
            throw new IOException("Replay frame of " + frame.getSerializedSize() + " bytes exceeds the frame limit");
        }
        frame.writeDelimitedTo(compressed);
    }

    @Override
    public void close() throws IOException {
        compressed.close();
    }
}
