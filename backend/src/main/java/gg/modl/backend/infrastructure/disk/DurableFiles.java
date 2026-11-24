package gg.modl.backend.infrastructure.disk;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

public final class DurableFiles {
    private static final int COPY_BUFFER_BYTES = 64 * 1024;
    private static final String POSIX = "posix";

    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY = PosixFilePermissions.fromString("rwx------");

    private DurableFiles() {
    }

    public static void createOwnerOnlyDirectories(Path directory) throws IOException {
        Files.createDirectories(directory);
        if (Files.getFileStore(directory).supportsFileAttributeView(POSIX)) {
            Files.setPosixFilePermissions(directory, OWNER_ONLY_DIRECTORY);
        }
    }

    public static void writeText(Path target, String content) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    public static long copyAtMost(InputStream data, Path target, long limit) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long written = 0;
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             OutputStream output = Channels.newOutputStream(channel)) {
            while (written <= limit) {
                int read = data.read(buffer, 0, (int) Math.min(buffer.length, limit - written + 1));
                if (read < 0) {
                    break;
                }
                output.write(buffer, 0, read);
                written += read;
            }
            output.flush();
            channel.force(true);
        }
        return written;
    }

    public static void forceDirectory(Path directory) throws IOException {
        if (!directory.getFileSystem().supportedFileAttributeViews().contains(POSIX)) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
