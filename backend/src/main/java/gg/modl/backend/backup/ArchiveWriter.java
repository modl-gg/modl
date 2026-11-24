package gg.modl.backend.backup;

import java.io.IOException;
import java.io.OutputStream;

@FunctionalInterface
public interface ArchiveWriter {
    void writeTo(OutputStream destination) throws IOException;
}
