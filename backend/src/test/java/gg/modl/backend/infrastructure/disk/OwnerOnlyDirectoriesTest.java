package gg.modl.backend.infrastructure.disk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnerOnlyDirectoriesTest {
    @TempDir
    Path directory;

    @Test
    void theDatabaseDirectoryIsCreatedAndTightenedToItsOwner() throws IOException {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path existing = Files.createDirectories(directory.resolve("existing"));
        Files.setPosixFilePermissions(existing, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path created = directory.resolve("data/database");

        DurableFiles.createOwnerOnlyDirectories(existing);
        DurableFiles.createOwnerOnlyDirectories(created);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(existing))).isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(created))).isEqualTo("rwx------");
    }
}
