package gg.modl.backend.replay.service;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import gg.modl.minecraft.replay.format.ReplayFileFormat;
import gg.modl.minecraft.replay.format.ReplayFileReader;
import gg.modl.minecraft.replay.format.ReplayFormatException;
import gg.modl.minecraft.replay.format.ReplayFrameVisitor;
import gg.modl.minecraft.replay.format.ReplayLimits;
import gg.modl.proto.modl.v1.ReplayBlockPalette;
import gg.modl.proto.modl.v1.ReplayEventBatch;
import gg.modl.proto.modl.v1.ReplayFrame;
import gg.modl.proto.modl.v1.ReplayHeader;
import gg.modl.proto.modl.v1.ReplayStringTable;
import gg.modl.proto.modl.v1.ReplayWorldSection;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.stereotype.Component;

@Component
public class ReplayFileValidator {
    public ReplayFileCheck check(InputStream file) throws IOException {
        try (ReplayFileReader reader = ReplayFileReader.open(file, ReplayLimits.MAX_UNCOMPRESSED_BYTES)) {
            FrameScan scan = new FrameScan();
            boolean scanned = true;
            while (scanned) {
                scanned = reader.scanNext(scan);
            }
            scan.finish();
            return ReplayFileCheck.valid(ReplayFileFormat.FORMAT_VERSION);
        } catch (ReplayFormatException e) {
            return ReplayFileCheck.invalid(String.valueOf(e.getMessage()));
        }
    }

    private static final class FrameScan implements ReplayFrameVisitor {
        private boolean started;
        private boolean ended;
        private int sections;
        private long events;
        private int keyframes;
        private int strings;
        private int states;
        private int blockEntities;

        @Override
        public void visit(CodedInputStream frame) throws IOException {
            if (ended) {
                throw problem("data after the end frame");
            }
            int tag = frame.readTag();
            if (tag == 0 || WireFormat.getTagWireType(tag) != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                throw problem("malformed frame");
            }
            int kind = WireFormat.getTagFieldNumber(tag);
            if (!started && kind != ReplayFrame.HEADER_FIELD_NUMBER) {
                throw problem("missing header");
            }
            int outer = frame.pushLimit(frame.readRawVarint32());
            visitBody(kind, frame);
            frame.popLimit(outer);
            if (frame.readTag() != 0) {
                throw problem("malformed frame");
            }
        }

        void finish() throws ReplayFormatException {
            if (!started) {
                throw problem("empty replay");
            }
            if (!ended) {
                throw problem("missing end frame");
            }
        }

        private void visitBody(int kind, CodedInputStream body) throws IOException {
            switch (kind) {
                case ReplayFrame.HEADER_FIELD_NUMBER -> header(body);
                case ReplayFrame.STRINGS_FIELD_NUMBER -> strings = bounded(strings
                    + count(body, ReplayStringTable.VALUES_FIELD_NUMBER), ReplayLimits.MAX_STRINGS, "too many strings");
                case ReplayFrame.BLOCK_PALETTE_FIELD_NUMBER -> states = bounded(states
                    + count(body, ReplayBlockPalette.STATES_FIELD_NUMBER), ReplayLimits.MAX_BLOCK_STATES, "too many block states");
                case ReplayFrame.SECTION_FIELD_NUMBER -> section(body);
                case ReplayFrame.BLOCK_ENTITY_FIELD_NUMBER -> {
                    blockEntities = bounded(blockEntities + 1, ReplayLimits.MAX_BLOCK_ENTITIES, "too many block entities");
                    skipAll(body);
                }
                case ReplayFrame.KEYFRAME_FIELD_NUMBER -> {
                    keyframes = bounded(keyframes + 1, ReplayLimits.MAX_KEYFRAMES, "too many keyframes");
                    skipAll(body);
                }
                case ReplayFrame.EVENTS_FIELD_NUMBER -> {
                    events += count(body, ReplayEventBatch.EVENTS_FIELD_NUMBER);
                    if (events > ReplayLimits.MAX_EVENTS) {
                        throw problem("too many events");
                    }
                }
                case ReplayFrame.END_FIELD_NUMBER -> {
                    skipAll(body);
                    ended = true;
                }
                default -> throw problem("unknown frame");
            }
        }

        private void header(CodedInputStream body) throws IOException {
            if (started) {
                throw problem("second header");
            }
            started = true;
            int version = 0;
            for (int tag = body.readTag(); tag != 0; tag = body.readTag()) {
                if (WireFormat.getTagFieldNumber(tag) == ReplayHeader.FORMAT_VERSION_FIELD_NUMBER
                    && WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_VARINT) {
                    version = body.readUInt32();
                } else {
                    body.skipField(tag);
                }
            }
            if (version != ReplayFileFormat.FORMAT_VERSION) {
                throw problem("header version mismatch");
            }
        }

        private void section(CodedInputStream body) throws IOException {
            sections = bounded(sections + 1, ReplayLimits.MAX_SECTIONS, "too many sections");
            int blocks = 0;
            for (int tag = body.readTag(); tag != 0; tag = body.readTag()) {
                if (WireFormat.getTagFieldNumber(tag) != ReplayWorldSection.STATES_FIELD_NUMBER) {
                    body.skipField(tag);
                } else if (WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    blocks += packedVarints(body, ReplayLimits.SECTION_STATES - blocks);
                } else {
                    body.skipField(tag);
                    blocks++;
                }
                if (blocks > ReplayLimits.SECTION_STATES) {
                    throw problem("malformed section");
                }
            }
            if (blocks != 0 && blocks != ReplayLimits.SECTION_STATES) {
                throw problem("malformed section");
            }
        }

        private static int packedVarints(CodedInputStream body, int allowed) throws IOException {
            int outer = body.pushLimit(body.readRawVarint32());
            int count = 0;
            while (!body.isAtEnd() && count <= allowed) {
                body.readRawVarint64();
                count++;
            }
            body.skipRawBytes(body.getBytesUntilLimit());
            body.popLimit(outer);
            return count;
        }

        private static int count(CodedInputStream body, int repeatedField) throws IOException {
            int count = 0;
            for (int tag = body.readTag(); tag != 0; tag = body.readTag()) {
                if (WireFormat.getTagFieldNumber(tag) == repeatedField) {
                    count++;
                }
                body.skipField(tag);
            }
            return count;
        }

        private static void skipAll(CodedInputStream body) throws IOException {
            for (int tag = body.readTag(); tag != 0; tag = body.readTag()) {
                body.skipField(tag);
            }
        }

        private static int bounded(long value, int limit, String problem) throws ReplayFormatException {
            if (value > limit) {
                throw problem(problem);
            }
            return (int) value;
        }

        private static ReplayFormatException problem(String problem) {
            return new ReplayFormatException(problem);
        }
    }
}
