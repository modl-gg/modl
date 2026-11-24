package gg.modl.minecraft.core.embedded;

import gg.modl.minecraft.core.util.Hex;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

final class PayloadBuilder {
    private final Path directory;
    private final List<String> lines = new ArrayList<>();

    PayloadBuilder(Path directory) {
        this.directory = directory;
    }

    PayloadBuilder packed(String fileName, byte[] content) throws IOException {
        Path library = directory.resolve(EmbeddedPayload.LIBRARY_DIRECTORY).resolve(fileName);
        Files.createDirectories(library.getParent());
        Files.write(library, content);
        return line("packed " + sha256(content) + " " + content.length + " " + fileName);
    }

    PayloadBuilder central(MavenArtifact artifact, byte[] content) {
        return line("central " + sha256(content) + " " + content.length + " " + artifact);
    }

    PayloadBuilder line(String line) {
        lines.add(line);
        return this;
    }

    PayloadSource write() throws IOException {
        Files.createDirectories(directory);
        StringBuilder index = new StringBuilder(ServerLibraryIndex.HEADER).append('\n');
        for (String line : lines) index.append(line).append('\n');
        Files.write(directory.resolve(ServerLibraryIndex.FILE_NAME), index.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(directory.resolve(EmbeddedPayload.VERSION_FILE), "3.0.0\n".getBytes(StandardCharsets.UTF_8));
        return name -> {
            Path file = directory.resolve(name);
            return Files.exists(file) ? url(file) : null;
        };
    }

    private static URL url(Path file) {
        try {
            return file.toUri().toURL();
        } catch (MalformedURLException invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    static String sha256(byte[] content) {
        try {
            return Hex.encode(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
