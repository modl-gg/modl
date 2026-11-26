package gg.modl.minecraft.replay.format;

import com.google.protobuf.CodedInputStream;

import java.io.IOException;

public interface ReplayFrameVisitor {
    void visit(CodedInputStream frame) throws IOException;
}
