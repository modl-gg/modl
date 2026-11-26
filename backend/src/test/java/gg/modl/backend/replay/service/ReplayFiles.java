package gg.modl.backend.replay.service;

import gg.modl.minecraft.replay.format.ReplayFileWriter;
import gg.modl.proto.modl.v1.ReplayEnd;
import gg.modl.proto.modl.v1.ReplayFrame;
import gg.modl.proto.modl.v1.ReplayHeader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

final class ReplayFiles {
    private ReplayFiles() {
    }

    static byte[] minimal() {
        return of(List.of(header(), end()));
    }

    static ReplayFrame header() {
        return ReplayFrame.newBuilder().setHeader(ReplayHeader.newBuilder().setFormatVersion(5).setReplayId("r")).build();
    }

    static ReplayFrame end() {
        return ReplayFrame.newBuilder().setEnd(ReplayEnd.getDefaultInstance()).build();
    }

    static byte[] of(List<ReplayFrame> frames) {
        return of(5, frames);
    }

    static byte[] of(int version, List<ReplayFrame> frames) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ReplayFileWriter writer = new ReplayFileWriter(out)) {
                for (ReplayFrame frame : frames) writer.write(frame);
            }
            byte[] file = out.toByteArray();
            file[4] = (byte) (version >>> 8);
            file[5] = (byte) version;
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
