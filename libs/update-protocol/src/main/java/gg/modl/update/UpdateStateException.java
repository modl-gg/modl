package gg.modl.update;

import java.io.IOException;
import java.nio.file.Path;

public final class UpdateStateException extends IOException {
    private static final long serialVersionUID = 1L;

    private final transient Path file;

    public UpdateStateException(Path file, String problem) {
        super(file + " " + problem + ". Move it away or restore it to start modl.");
        this.file = file;
    }

    public UpdateStateException(Path file, String problem, Throwable cause) {
        super(file + " " + problem + ". Move it away or restore it to start modl.", cause);
        this.file = file;
    }

    public Path file() {
        return file;
    }
}
