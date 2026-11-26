package gg.modl.backend.support.sql;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.testcontainers.containers.GenericContainer;

final class ContainerStarts {
    private static final Path LOCK_FILE = Path.of(System.getProperty("java.io.tmpdir"),
        "modl-test-containers-" + System.getProperty("user.name") + ".lock");

    private ContainerStarts() {
    }

    static synchronized <T extends GenericContainer<?>> T start(T container) {
        try (FileChannel channel = FileChannel.open(LOCK_FILE, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            container.start();
            return container;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not lock " + LOCK_FILE + " to start " + container.getDockerImageName(), e);
        }
    }
}
