package gg.modl.minecraft.core.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;

public final class OwnerOnlyFiles {
    private static final String DIRECTORY_PERMISSIONS = "rwx------";
    private static final String FILE_PERMISSIONS = "rw-------";

    private OwnerOnlyFiles() {
    }

    public static Path createDirectories(Path directory) throws IOException {
        Files.createDirectories(directory);
        if (supportsPosix(directory)) restrictToOwner(directory);
        return directory;
    }

    private static void restrictToOwner(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString(DIRECTORY_PERMISSIONS));
        } catch (IOException | UnsupportedOperationException | SecurityException refused) {
            return;
        }
    }

    public static OutputStream createFile(Path file) throws IOException {
        Path folder = file.toAbsolutePath().getParent();
        if (folder != null && supportsPosix(folder)) {
            return Channels.newOutputStream(Files.newByteChannel(file, EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(FILE_PERMISSIONS))));
        }
        return Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static boolean supportsPosix(Path path) {
        return Files.getFileAttributeView(path, PosixFileAttributeView.class) != null;
    }
}
