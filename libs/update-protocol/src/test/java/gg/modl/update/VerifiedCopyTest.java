package gg.modl.update;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifiedCopyTest {
    private static final ReleaseVersion VERSION = ReleaseVersion.parse("3.0.1");
    private static final byte[] RELEASED = "the signed server bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TAMPERED = "attacker controlled bytes".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temp;

    @Test
    void theCopyKeepsTheVerifiedBytesWhenTheSourceIsReplacedAfterTheHash() throws IOException {
        Path source = released();

        try (VerifiedCopy copy = VerifiedCopy.of(source, artifact(), runDirectory())) {
            Files.write(source, TAMPERED);

            assertArrayEquals(RELEASED, Files.readAllBytes(copy.file()));
            assertFalse(copy.file().equals(source));
        }
    }

    @Test
    void aSourceThatNoLongerMatchesIsNeverCopied() throws IOException {
        Path source = released();
        Files.write(source, TAMPERED);

        assertThrows(ArtifactMismatchException.class, () -> VerifiedCopy.of(source, artifact(), runDirectory()));
        assertTrue(isEmpty(runDirectory()));
    }

    @Test
    void aLargerSourceStopsAtTheListedSize() throws IOException {
        Path source = released();
        Files.write(source, (new String(RELEASED, StandardCharsets.UTF_8) + "!").getBytes(StandardCharsets.UTF_8));

        assertThrows(ArtifactMismatchException.class, () -> VerifiedCopy.of(source, artifact(), runDirectory()));
    }

    @Test
    void closingRemovesTheCopy() throws IOException {
        VerifiedCopy copy = VerifiedCopy.of(released(), artifact(), runDirectory());

        copy.close();

        assertFalse(Files.exists(copy.file()));
    }

    @Test
    void aCopyInUseSurvivesTheCleanupOfAnotherOneAndAnAbandonedCopyDoesNot() throws IOException {
        Path abandoned = Files.createDirectories(runDirectory().resolve("abandoned"));
        Files.write(abandoned.resolve(VerifiedCopy.LOCK_FILE), new byte[0]);
        Path source = released();

        try (VerifiedCopy first = VerifiedCopy.of(source, artifact(), runDirectory());
             VerifiedCopy second = VerifiedCopy.of(source, artifact(), runDirectory())) {
            assertTrue(Files.exists(first.file()));
            assertTrue(Files.exists(second.file()));
            assertFalse(Files.exists(abandoned));
        }
    }

    private Path released() throws IOException {
        Path directory = Files.createDirectories(temp.resolve("versions").resolve(VERSION.toString()));
        return Files.write(directory.resolve(ArtifactKind.SERVER.fileName(VERSION)), RELEASED);
    }

    private Path runDirectory() {
        return temp.resolve("run");
    }

    private static ReleaseArtifact artifact() {
        return new ReleaseArtifact(ArtifactKind.SERVER, ArtifactKind.SERVER.fileName(VERSION), sha256(RELEASED), RELEASED.length);
    }

    private static String sha256(byte[] content) {
        return Hex.encode(ArtifactFiles.newDigest().digest(content));
    }

    private static boolean isEmpty(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return true;
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return !entries.findAny().isPresent();
        }
    }
}
