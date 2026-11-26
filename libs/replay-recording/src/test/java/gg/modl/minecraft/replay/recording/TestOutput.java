package gg.modl.minecraft.replay.recording;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class TestOutput implements ReplayOutput {
    private final Path directory;

    TestOutput(Path directory) {
        this.directory = directory;
    }

    @Override
    public Path directory() {
        return directory;
    }

    @Override
    public OutputStream create(Path file) throws IOException {
        Files.createDirectories(directory);
        return Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
}
