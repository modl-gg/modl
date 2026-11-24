package gg.modl.minecraft.replay.api;

import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;

public class FileReplayOutput implements ReplayOutput {

    private final File directory;
    private final boolean compress;
    @Getter
    private @Nullable File outputFile;

    public FileReplayOutput(File directory) {
        this(directory, true);
    }

    public FileReplayOutput(File directory, boolean compress) {
        this.directory = directory;
        this.compress = compress;
    }

    @Override
    public OutputStream openStream() throws IOException {
        directory.mkdirs();
        File file = new File(directory, "replay-" + System.currentTimeMillis() + ".modlreplay");
        outputFile = file;
        OutputStream out = new FileOutputStream(file);
        return compress ? new GZIPOutputStream(out) : out;
    }

    @Override
    public void onComplete(ReplayMetadata metadata) {
    }

    @Override
    public void onError(Exception e) {
        File file = outputFile;
        if (file != null && file.exists()) {
            file.delete();
        }
    }
}
