package gg.modl.minecraft.core.imports;

import java.io.IOException;
import java.nio.file.Path;

public interface ImportFileUpload {
    int send(String jobId, Path file) throws IOException;

    boolean isStalled();

    void abort();
}
