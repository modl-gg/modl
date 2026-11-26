package gg.modl.minecraft.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OwnerOnlyFilesTest {
    @TempDir
    Path folder;

    @Test
    void directoriesAndFilesAreReadableOnlyByTheOwner() throws IOException {
        assumeTrue(Files.getFileStore(folder).supportsFileAttributeView(PosixFileAttributeView.class));
        Path directory = OwnerOnlyFiles.createDirectories(folder.resolve("replays").resolve("nested"));
        Path file = directory.resolve("one.partial");
        try (OutputStream out = OwnerOnlyFiles.createFile(file)) {
            out.write(1);
        }

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertEquals(1, Files.size(file));
    }

    @Test
    void anExistingFileIsNeverReused() throws IOException {
        Path file = Files.write(folder.resolve("taken"), new byte[]{1});

        assertThrows(FileAlreadyExistsException.class, () -> OwnerOnlyFiles.createFile(file).close());
    }
}
