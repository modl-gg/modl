package gg.modl.backend.instance.setup;

import gg.modl.backend.database.DataDirectoryProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class SetupCodeFile {
    static final String DEFAULT_FILE_NAME = "setup-code.txt";
    private static final String TEMPORARY_PREFIX = ".setup-code-";
    private static final String TEMPORARY_SUFFIX = ".tmp";
    private static final String POSIX_VIEW = "posix";
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private final Path path;
    private final Path workingDirectory;

    @Autowired
    public SetupCodeFile(SetupProperties setupProperties, DataDirectoryProperties dataDirectoryProperties) {
        this(setupProperties.configuredCodeFile().map(Path::of).orElseGet(() -> dataDirectoryProperties.root().resolve(DEFAULT_FILE_NAME)),
            Path.of("").toAbsolutePath());
    }

    SetupCodeFile(Path path, Path workingDirectory) {
        this.path = path.toAbsolutePath().normalize();
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
    }

    public Optional<String> write(String content) {
        try {
            Files.createDirectories(path.getParent());
            Path temporary = createOwnerOnlyTemporaryFile();
            try {
                Files.writeString(temporary, content, StandardCharsets.UTF_8);
                moveIntoPlace(temporary);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return Optional.of(displayPath());
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            log.warn("Could not write the setup code file {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    public void delete() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | SecurityException e) {
            log.warn("Could not delete the setup code file {}: {}", path, e.getMessage());
        }
    }

    private String displayPath() {
        return path.startsWith(workingDirectory) ? relativePath() : path.toString();
    }

    private String relativePath() {
        return workingDirectory.relativize(path).toString();
    }

    private Path createOwnerOnlyTemporaryFile() throws IOException {
        Path directory = path.getParent();
        if (FileSystems.getDefault().supportedFileAttributeViews().contains(POSIX_VIEW)) {
            return Files.createTempFile(directory, TEMPORARY_PREFIX, TEMPORARY_SUFFIX, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        }
        return Files.createTempFile(directory, TEMPORARY_PREFIX, TEMPORARY_SUFFIX);
    }

    private void moveIntoPlace(Path temporary) throws IOException {
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
