package gg.modl.backend.tunnel.cloudflared;

import gg.modl.backend.backup.FileTrees;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public record TunnelDirectory(Path root) {
    public static final String NAME = "tunnel";
    static final String CONFIG_FILE = "config.yml";
    static final String PID_FILE = "cloudflared.pid";

    public static TunnelDirectory in(Path dataDirectory) {
        return new TunnelDirectory(dataDirectory.resolve(NAME).toAbsolutePath().normalize());
    }

    Path binary(CloudflaredBuild build) {
        return root.resolve(build.file());
    }

    Path config() {
        return root.resolve(CONFIG_FILE);
    }

    Path pidFile() {
        return root.resolve(PID_FILE);
    }

    void retainOnly(CloudflaredBuild build) throws IOException {
        Files.createDirectories(root);
        Set<Path> kept = Set.of(binary(build), config(), pidFile());
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                if (!kept.contains(entry)) {
                    delete(entry);
                }
            }
        }
    }

    private static void delete(Path entry) throws IOException {
        if (Files.isDirectory(entry) && !Files.isSymbolicLink(entry)) {
            FileTrees.deleteRecursively(entry);
            return;
        }
        Files.deleteIfExists(entry);
    }
}
