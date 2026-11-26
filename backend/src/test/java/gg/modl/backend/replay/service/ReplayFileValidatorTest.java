package gg.modl.backend.replay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;
import com.sun.management.ThreadMXBean;
import gg.modl.minecraft.replay.format.ReplayFileFormat;
import gg.modl.minecraft.replay.format.ReplayLimits;
import gg.modl.proto.modl.v1.ReplayBlockEntity;
import gg.modl.proto.modl.v1.ReplayBlockPalette;
import gg.modl.proto.modl.v1.ReplayBlockState;
import gg.modl.proto.modl.v1.ReplayEventBatch;
import gg.modl.proto.modl.v1.ReplayFrame;
import gg.modl.proto.modl.v1.ReplayHeader;
import gg.modl.proto.modl.v1.ReplayKeyframe;
import gg.modl.proto.modl.v1.ReplayStringTable;
import gg.modl.proto.modl.v1.ReplayWorldSection;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReplayFileValidatorTest {
    private static final byte[] EMPTY_EVENT = {(byte) (ReplayEventBatch.EVENTS_FIELD_NUMBER << 3 | WireFormat.WIRETYPE_LENGTH_DELIMITED), 0};

    private final ReplayFileValidator validator = new ReplayFileValidator();

    @Test
    void aCompleteVersionFiveReplayIsAccepted() throws IOException {
        assertThat(problem(ReplayFiles.minimal())).isNull();
    }

    @Test
    void aValidReplayReportsItsFormatVersion() throws IOException {
        assertThat(validator.check(new ByteArrayInputStream(ReplayFiles.minimal())).formatVersion()).isEqualTo(5);
    }

    @Test
    void anotherMagicOrVersionIsRefused() throws IOException {
        assertThat(problem(new byte[]{0x1f, (byte) 0x8b, 8, 0, 0, 0})).isEqualTo("Not a modl replay file");
        assertThat(problem(ReplayFiles.of(4, List.of(ReplayFiles.header(), ReplayFiles.end())))).isEqualTo("Unsupported replay format version 4");
    }

    @Test
    void aReplayWithoutHeaderOrEndIsRefused() throws IOException {
        assertThat(problem(ReplayFiles.of(List.of(ReplayFiles.end())))).isEqualTo("missing header");
        assertThat(problem(ReplayFiles.of(List.of(ReplayFiles.header())))).isEqualTo("missing end frame");
        assertThat(problem(ReplayFiles.of(List.of(ReplayFiles.header(), ReplayFiles.end(), ReplayFiles.end()))))
            .isEqualTo("data after the end frame");
    }

    @Test
    void aHeaderWithAnotherVersionIsRefused() throws IOException {
        ReplayFrame header = ReplayFrame.newBuilder().setHeader(ReplayHeader.newBuilder().setFormatVersion(6)).build();
        assertThat(problem(ReplayFiles.of(List.of(header, ReplayFiles.end())))).isEqualTo("header version mismatch");
    }

    @Test
    void aSectionMustHoldEveryBlock() throws IOException {
        ReplayFrame section = ReplayFrame.newBuilder().setSection(ReplayWorldSection.newBuilder().addStates(1)).build();
        assertThat(problem(ReplayFiles.of(List.of(ReplayFiles.header(), section, ReplayFiles.end())))).isEqualTo("malformed section");
    }

    @Test
    void aTruncatedFileIsRefused() throws IOException {
        byte[] file = ReplayFiles.minimal();
        assertThat(problem(Arrays.copyOf(file, file.length - 12))).isNotNull();
        assertThat(problem(Arrays.copyOf(file, 3))).isEqualTo("Replay file is truncated");
    }

    @ParameterizedTest
    @ValueSource(strings = {"latest", "1.21", "legacy"})
    void everyCapturedFixtureIsAccepted(String line) throws IOException {
        Path fixture = Path.of("..", "libs", "replay-format", "src", "test", "resources", "fixtures", "v5", line + ".modlreplay");
        assertThat(problem(Files.readAllBytes(fixture))).isNull();
    }

    @Test
    void aFrameOfManyEmptyEventsIsCountedWithoutAllocatingAnEventEach() throws IOException {
        int eventsPerFrame = (ReplayLimits.MAX_FRAME_BYTES - 16) / EMPTY_EVENT.length;
        int frames = (int) (ReplayLimits.MAX_EVENTS / eventsPerFrame) + 1;
        byte[] file = rawFile(frames, eventBatch(eventsPerFrame));
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();

        String problem = problem(file);

        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertThat(problem).isEqualTo("too many events");
        assertThat((long) eventsPerFrame * frames).isGreaterThan(ReplayLimits.MAX_EVENTS);
        assertThat(allocated).isLessThan(ReplayLimits.MAX_FRAME_BYTES);
    }

    @Test
    void everyCountedLimitIsEnforced() throws IOException {
        ReplayFrame keyframe = ReplayFrame.newBuilder().setKeyframe(ReplayKeyframe.getDefaultInstance()).build();
        ReplayFrame blockEntity = ReplayFrame.newBuilder().setBlockEntity(ReplayBlockEntity.getDefaultInstance()).build();
        ReplayFrame strings = ReplayFrame.newBuilder().setStrings(ReplayStringTable.newBuilder()
            .addAllValues(Collections.nCopies(ReplayLimits.MAX_STRINGS + 1, ""))).build();
        ReplayFrame palette = ReplayFrame.newBuilder().setBlockPalette(ReplayBlockPalette.newBuilder()
            .addAllStates(Collections.nCopies(ReplayLimits.MAX_BLOCK_STATES + 1, ReplayBlockState.getDefaultInstance()))).build();

        assertThat(problem(ReplayFiles.of(framed(keyframe, ReplayLimits.MAX_KEYFRAMES + 1)))).isEqualTo("too many keyframes");
        assertThat(problem(ReplayFiles.of(framed(blockEntity, ReplayLimits.MAX_BLOCK_ENTITIES + 1)))).isEqualTo("too many block entities");
        assertThat(problem(ReplayFiles.of(framed(strings, 1)))).isEqualTo("too many strings");
        assertThat(problem(ReplayFiles.of(framed(palette, 1)))).isEqualTo("too many block states");
        assertThat(problem(ReplayFiles.of(framed(keyframe, ReplayLimits.MAX_KEYFRAMES)))).isNull();
    }

    @Test
    void aFullPackedSectionIsAccepted() throws IOException {
        ReplayFrame section = ReplayFrame.newBuilder().setSection(ReplayWorldSection.newBuilder()
            .addAllStates(Collections.nCopies(ReplayLimits.SECTION_STATES, 300))).build();
        ReplayFrame tooBig = ReplayFrame.newBuilder().setSection(ReplayWorldSection.newBuilder()
            .addAllStates(Collections.nCopies(ReplayLimits.SECTION_STATES + 1, 1))).build();

        assertThat(problem(ReplayFiles.of(List.of(ReplayFiles.header(), section, ReplayFiles.end())))).isNull();
        assertThat(problem(ReplayFiles.of(List.of(ReplayFiles.header(), tooBig, ReplayFiles.end())))).isEqualTo("malformed section");
    }

    @Test
    void aReadFailureOfTheSourceIsNotAFormatProblem() {
        byte[] file = ReplayFiles.minimal();
        InputStream failing = new SequenceInputStream(new ByteArrayInputStream(file, 0, 8), new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("connection reset");
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                throw new IOException("connection reset");
            }
        });

        assertThatThrownBy(() -> validator.check(failing)).isInstanceOf(IOException.class).hasMessage("connection reset");
    }

    private static List<ReplayFrame> framed(ReplayFrame frame, int copies) {
        List<ReplayFrame> frames = new ArrayList<>();
        frames.add(ReplayFiles.header());
        frames.addAll(Collections.nCopies(copies, frame));
        frames.add(ReplayFiles.end());
        return frames;
    }

    private static byte[] eventBatch(int events) {
        ByteArrayOutputStream batch = new ByteArrayOutputStream();
        for (int index = 0; index < events; index++) {
            batch.writeBytes(EMPTY_EVENT);
        }
        return batch.toByteArray();
    }

    private static byte[] rawFile(int eventFrames, byte[] batch) throws IOException {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(ReplayFileFormat.magic());
        file.write(ReplayFileFormat.versionBytes());
        try (GZIPOutputStream gzip = new GZIPOutputStream(file)) {
            ReplayFiles.header().writeDelimitedTo(gzip);
            CodedOutputStream frame = CodedOutputStream.newInstance(gzip);
            int body = CodedOutputStream.computeTagSize(ReplayFrame.EVENTS_FIELD_NUMBER) + CodedOutputStream.computeUInt32SizeNoTag(batch.length)
                + batch.length;
            for (int index = 0; index < eventFrames; index++) {
                frame.writeUInt32NoTag(body);
                frame.writeTag(ReplayFrame.EVENTS_FIELD_NUMBER, WireFormat.WIRETYPE_LENGTH_DELIMITED);
                frame.writeUInt32NoTag(batch.length);
                frame.writeRawBytes(batch);
            }
            frame.flush();
            ReplayFiles.end().writeDelimitedTo(gzip);
        }
        return file.toByteArray();
    }

    private String problem(byte[] file) throws IOException {
        return validator.check(new ByteArrayInputStream(file)).problem();
    }
}
