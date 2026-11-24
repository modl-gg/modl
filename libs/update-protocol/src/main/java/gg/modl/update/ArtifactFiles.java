package gg.modl.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class ArtifactFiles {
    private static final int BUFFER_BYTES = 64 * 1024;

    private ArtifactFiles() {
    }

    public static void requireMatches(Path file, ReleaseArtifact artifact) throws IOException {
        if (!file.getFileName().toString().equals(artifact.file())) {
            throw new ArtifactMismatchException(file + " is not named " + artifact.file());
        }
        if (!Files.isRegularFile(file)) {
            throw new ArtifactMismatchException(file + " is missing");
        }
        long size = Files.size(file);
        if (size != artifact.size()) {
            throw new ArtifactMismatchException(file + " has " + size + " bytes, the release lists " + artifact.size());
        }
        String digest = sha256(file);
        if (!digest.equals(artifact.sha256())) {
            throw new ArtifactMismatchException(file + " does not match the SHA-256 the release lists");
        }
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[BUFFER_BYTES];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return Hex.encode(digest.digest());
    }

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public static String hex(byte[] digest) {
        return Hex.encode(digest);
    }
}
