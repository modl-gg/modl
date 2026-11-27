package gg.modl.minecraft.replay.format;

import com.google.protobuf.CodedInputStream;
import gg.modl.proto.modl.v1.ReplayEnd;
import gg.modl.proto.modl.v1.ReplayFrame;
import gg.modl.proto.modl.v1.ReplayHeader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayFileReaderTest {
    private static final ReplayFrame HEADER = ReplayFrame.newBuilder()
        .setHeader(ReplayHeader.newBuilder().setFormatVersion(ReplayFileFormat.FORMAT_VERSION).setReplayId("r")).build();
    private static final ReplayFrame END = ReplayFrame.newBuilder().setEnd(ReplayEnd.newBuilder().setDurationMs(7)).build();

    @Test
    void aScanVisitsEveryFrameWithinItsOwnBounds() throws IOException {
        List<Integer> sizes = new ArrayList<>();
        try (ReplayFileReader reader = ReplayFileReader.open(new ByteArrayInputStream(file(HEADER, END)), ReplayLimits.MAX_UNCOMPRESSED_BYTES)) {
            boolean scanned = true;
            while (scanned) {
                scanned = reader.scanNext(frame -> sizes.add(frame.getBytesUntilLimit()));
            }
        }
        assertEquals(Arrays.asList(HEADER.getSerializedSize(), END.getSerializedSize()), sizes);
    }

    @Test
    void aFrameLeftHalfReadIsSkippedBeforeTheNextOne() throws IOException {
        try (ReplayFileReader reader = ReplayFileReader.open(new ByteArrayInputStream(file(HEADER, END)), ReplayLimits.MAX_UNCOMPRESSED_BYTES)) {
            assertTrue(reader.scanNext(CodedInputStream::readTag));
            assertEquals(END, reader.next());
            assertNull(reader.next());
        }
    }

    @Test
    void dataAfterTheCompressedFramesIsRefused() throws IOException {
        byte[] file = file(HEADER, END);
        for (int extra : new int[] {1, 3, 20, 40}) {
            byte[] padded = Arrays.copyOf(file, file.length + extra);
            try (ReplayFileReader reader = ReplayFileReader.open(new ByteArrayInputStream(padded), ReplayLimits.MAX_UNCOMPRESSED_BYTES)) {
                assertEquals(HEADER, reader.next());
                assertEquals(END, reader.next());
                assertThrows(ReplayFormatException.class, reader::next, extra + " trailing bytes");
            }
        }
    }

    @Test
    void dataAfterTheCompressedFramesIsRefusedWhenTheSourceArrivesInSmallReads() throws IOException {
        byte[] file = file(HEADER, END);
        for (int extra : new int[] {0, 1, 3, 20}) {
            byte[] padded = Arrays.copyOf(file, file.length + extra);
            try (ReplayFileReader reader = ReplayFileReader.open(smallReads(padded, 7), ReplayLimits.MAX_UNCOMPRESSED_BYTES)) {
                assertEquals(HEADER, reader.next());
                assertEquals(END, reader.next());
                if (extra == 0) assertNull(reader.next());
                else assertThrows(ReplayFormatException.class, reader::next, extra + " trailing bytes");
            }
        }
    }

    @Test
    void aDamagedTrailerAHeaderWithExtraFieldsAndASecondMemberAreRefused() throws IOException {
        byte[] file = file(HEADER, END);
        byte[] wrongChecksum = file.clone();
        wrongChecksum[file.length - 8] ^= 1;
        byte[] wrongSize = file.clone();
        wrongSize[file.length - 1] ^= 1;
        byte[] namedHeader = file.clone();
        namedHeader[ReplayFileFormat.prefixLength() + 3] = 0x08;
        ByteArrayOutputStream emptyMember = new ByteArrayOutputStream();
        new GZIPOutputStream(emptyMember).close();
        byte[] twoMembers = Arrays.copyOf(file, file.length + emptyMember.size());
        System.arraycopy(emptyMember.toByteArray(), 0, twoMembers, file.length, emptyMember.size());
        for (byte[] damaged : Arrays.asList(wrongChecksum, wrongSize, namedHeader, twoMembers)) {
            assertThrows(ReplayFormatException.class, () -> readAll(new ByteArrayInputStream(damaged)));
            assertThrows(ReplayFormatException.class, () -> readAll(smallReads(damaged, 7)));
        }
    }

    private static int readAll(InputStream source) throws IOException {
        int frames = 0;
        try (ReplayFileReader reader = ReplayFileReader.open(source, ReplayLimits.MAX_UNCOMPRESSED_BYTES)) {
            while (reader.next() != null) frames++;
        }
        return frames;
    }

    private static InputStream smallReads(byte[] content, int maxRead) {
        return new FilterInputStream(new ByteArrayInputStream(content)) {
            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                return super.read(buffer, offset, Math.min(length, maxRead));
            }
        };
    }

    @Test
    void aFileOfExactlyTheUncompressedLimitIsRead() throws IOException {
        byte[] file = file(HEADER, END);
        long size = uncompressedSize(file);
        try (ReplayFileReader reader = ReplayFileReader.open(new ByteArrayInputStream(file), size)) {
            assertEquals(HEADER, reader.next());
            assertEquals(END, reader.next());
            assertNull(reader.next());
        }
        try (ReplayFileReader reader = ReplayFileReader.open(new ByteArrayInputStream(file), size - 1)) {
            assertEquals(HEADER, reader.next());
            assertThrows(ReplayFormatException.class, reader::next);
        }
    }

    @Test
    void aFailureOfTheSourceIsPassedOnUnchanged() throws IOException {
        IOException failure = new IOException("storage went away");
        byte[] file = file(HEADER, END);
        InputStream failing = new FilterInputStream(new ByteArrayInputStream(file, 0, file.length - 4)) {
            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int read = super.read(buffer, offset, length);
                if (read < 0) throw failure;
                return read;
            }

            @Override
            public int read() throws IOException {
                int read = super.read();
                if (read < 0) throw failure;
                return read;
            }
        };

        IOException thrown = assertThrows(IOException.class, () -> drain(failing));

        assertSame(failure, thrown);
    }

    @Test
    void everyDecodingProblemIsAFormatProblem() throws IOException {
        byte[] file = file(HEADER, END);
        byte[] truncated = Arrays.copyOf(file, file.length - 12);
        byte[] shortPrefix = Arrays.copyOf(file, 3);
        byte[] garbled = file.clone();
        garbled[file.length - 10] ^= (byte) 0xFF;

        assertFalse(readsCleanly(truncated));
        assertFalse(readsCleanly(shortPrefix));
        assertFalse(readsCleanly(garbled));
    }

    private static boolean readsCleanly(byte[] file) throws IOException {
        try {
            drain(new ByteArrayInputStream(file));
            return true;
        } catch (ReplayFormatException expected) {
            return false;
        }
    }

    private static int drain(InputStream file) throws IOException {
        int frames = 0;
        try (ReplayFileReader reader = ReplayFileReader.open(file, ReplayLimits.MAX_UNCOMPRESSED_BYTES)) {
            while (reader.next() != null) {
                frames++;
            }
        }
        return frames;
    }

    private static long uncompressedSize(byte[] file) throws IOException {
        try (InputStream gzip = new GZIPInputStream(new ByteArrayInputStream(file, ReplayFileFormat.prefixLength(),
            file.length - ReplayFileFormat.prefixLength()))) {
            return gzip.readAllBytes().length;
        }
    }

    private static byte[] file(ReplayFrame... frames) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ReplayFileWriter writer = new ReplayFileWriter(out)) {
            for (ReplayFrame frame : frames) writer.write(frame);
        }
        return out.toByteArray();
    }
}
