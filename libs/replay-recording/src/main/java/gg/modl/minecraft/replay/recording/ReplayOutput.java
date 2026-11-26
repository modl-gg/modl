package gg.modl.minecraft.replay.recording;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

public interface ReplayOutput {
    Path directory();

    OutputStream create(Path file) throws IOException;
}
