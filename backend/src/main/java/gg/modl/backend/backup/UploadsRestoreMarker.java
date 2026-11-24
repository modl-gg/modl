package gg.modl.backend.backup;

import gg.modl.backend.infrastructure.util.FailureDescriptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class UploadsRestoreMarker {
    public static final String FILE_NAME = "restore-uploads-pending.txt";

    private final Path file;

    public UploadsRestoreMarker(Path dataRoot) {
        this.file = dataRoot.resolve(FILE_NAME);
    }

    public void begin(Path backup) throws IOException {
        BackupFiles.writeAtomically(file, output -> output.write(backup.toString().getBytes(StandardCharsets.UTF_8)));
    }

    public void clear() throws IOException {
        Files.deleteIfExists(file);
    }

    public Optional<String> warning() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        return Optional.of("A restore of " + interruptedBackup() + " stopped before it finished replacing the uploaded files, so the"
            + " database and the uploaded files may not match. Stop modl and run the same restore again, or delete " + file
            + " to keep both as they are.");
    }

    private String interruptedBackup() {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return "a backup (its name cannot be read: " + FailureDescriptions.describe(e) + ")";
        }
    }
}
