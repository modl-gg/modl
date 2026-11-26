package gg.modl.minecraft.replay.recording;

import com.google.protobuf.CodedOutputStream;
import gg.modl.minecraft.replay.format.ReplayFileWriter;
import gg.modl.minecraft.replay.format.ReplayLimits;
import gg.modl.minecraft.replay.recording.ReplayTooLargeException.Overflow;
import gg.modl.proto.modl.v1.ReplayFrame;

import java.io.Closeable;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

final class BoundedReplayWriter implements Closeable {
    private final ReplayBounds bounds;
    private final CountingOutputStream counted;
    private final ReplayFileWriter writer;
    private long uncompressedBytes;
    private long events;
    private int sections;
    private int keyframes;
    private int blockStates;
    private int strings;
    private int blockEntities;

    BoundedReplayWriter(OutputStream target, ReplayBounds bounds) throws IOException {
        this.bounds = bounds;
        this.counted = new CountingOutputStream(target);
        this.writer = new ReplayFileWriter(counted);
    }

    void write(ReplayFrame frame) throws IOException {
        int size = frame.getSerializedSize();
        check(size <= ReplayLimits.MAX_FRAME_BYTES, "frame size", Overflow.SIZE);
        uncompressedBytes += size + CodedOutputStream.computeUInt32SizeNoTag(size);
        check(uncompressedBytes <= bounds.getUncompressedBytes(), "unpacked size", Overflow.SIZE);
        switch (frame.getFrameCase()) {
            case EVENTS:
                events += frame.getEvents().getEventsCount();
                check(events <= bounds.getEvents(), "events", Overflow.HISTORY);
                break;
            case SECTION:
                check(++sections <= bounds.getSections(), "sections", Overflow.TERRAIN);
                break;
            case KEYFRAME:
                check(++keyframes <= bounds.getKeyframes(), "keyframes", Overflow.HISTORY);
                break;
            case BLOCK_PALETTE:
                blockStates += frame.getBlockPalette().getStatesCount();
                check(blockStates <= bounds.getBlockStates(), "block states", Overflow.TERRAIN);
                break;
            case STRINGS:
                strings += frame.getStrings().getValuesCount();
                check(strings <= bounds.getStrings(), "strings", Overflow.SIZE);
                break;
            case BLOCK_ENTITY:
                check(++blockEntities <= bounds.getBlockEntities(), "block entities", Overflow.TERRAIN);
                break;
            default:
                break;
        }
        writer.write(frame);
        check(counted.count <= bounds.getCompressedBytes(), "file size", Overflow.SIZE);
    }

    @Override
    public void close() throws IOException {
        writer.close();
        check(counted.count <= bounds.getCompressedBytes(), "file size", Overflow.SIZE);
    }

    private static void check(boolean within, String limit, Overflow overflow) throws ReplayTooLargeException {
        if (!within) throw new ReplayTooLargeException(limit, overflow);
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        private long count;

        private CountingOutputStream(OutputStream target) {
            super(target);
        }

        @Override
        public void write(int value) throws IOException {
            out.write(value);
            count++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length);
            count += length;
        }
    }
}
