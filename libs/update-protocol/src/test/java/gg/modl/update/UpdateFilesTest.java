package gg.modl.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateFilesTest {
    private static final Instant NOW = Instant.parse("2026-12-01T12:00:00Z");

    @TempDir
    Path dataDirectory;

    @Test
    void writesAndReadsStateAtomically() throws Exception {
        UpdateFiles files = new UpdateFiles(dataDirectory, new GsonJson());
        UpdateTransaction pending = UpdateTransaction.builder("id-1", TransactionKind.JAR, ReleaseVersion.parse("3.0.0"),
                ReleaseVersion.parse("3.0.1"), NOW)
            .managed(true).toArtifact(files.relativeArtifact(ReleaseVersion.parse("3.0.1"), ArtifactKind.SERVER))
            .backup("pre-update-20261201-120000.zip", "d".repeat(64)).schemaChanged(true).build();

        files.writePending(pending);
        UpdateTransaction read = files.readPending();

        assertEquals("id-1", read.id());
        assertEquals(ReleaseVersion.parse("3.0.1"), read.to());
        assertEquals("versions/3.0.1/modl-server-3.0.1.jar", read.toArtifact());
        assertEquals(true, read.schemaChanged());
        assertFalse(Files.exists(files.pendingFile().resolveSibling(UpdateFiles.PENDING + ".tmp")));
        if (Files.getFileStore(files.pendingFile()).supportsFileAttributeView("posix")) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(files.pendingFile()));
        }
    }

    @Test
    void missingFilesReadAsAbsent() throws Exception {
        UpdateFiles files = new UpdateFiles(dataDirectory, new GsonJson());

        assertNull(files.readPending());
        assertNull(files.readCommitted());
        assertNull(files.readLastFailure());
        assertFalse(files.hasState());
    }

    @Test
    void corruptFilesFailClosedAndNameTheFile() throws Exception {
        UpdateFiles files = new UpdateFiles(dataDirectory, new GsonJson());
        Files.createDirectories(files.root());
        Files.write(files.pendingFile(), "{not json".getBytes(StandardCharsets.UTF_8));

        UpdateStateException failure = assertThrows(UpdateStateException.class, files::readPending);
        assertTrue(failure.getMessage().contains(files.pendingFile().toString()));

        Files.write(files.committedFile(), new byte[UpdateFiles.MAX_STATE_BYTES + 1]);
        assertThrows(UpdateStateException.class, files::readCommitted);
    }

    @Test
    void artifactPathsCannotEscapeTheVersionsFolder() {
        UpdateFiles files = new UpdateFiles(dataDirectory, new GsonJson());

        assertThrows(UpdateStateException.class, () -> files.resolve("../../etc/passwd"));
        assertThrows(UpdateStateException.class, () -> files.resolve("versions/../pending.json"));
        assertThrows(UpdateStateException.class, () -> files.versionOf("versions/not-a-version/modl.jar"));
    }

    @Test
    void committedTransactionWinsWhenReadingState() throws Exception {
        UpdateFiles files = new UpdateFiles(dataDirectory, new GsonJson());
        UpdateTransaction pending = UpdateTransaction.builder("id-2", TransactionKind.JAR, ReleaseVersion.parse("3.0.0"),
            ReleaseVersion.parse("3.0.1"), NOW).phase(UpdatePhase.STARTING).build();
        files.writePending(pending);
        files.writeCommitted(CommittedUpdate.of(pending, NOW));

        UpdateState state = files.readState();

        assertNull(state.pending());
        assertTrue(state.pendingAlreadyCommitted());
        assertEquals("id-2", state.committed().transactionId());
    }

    @Test
    void deletesOtherVersionFolders() throws Exception {
        UpdateFiles files = new UpdateFiles(dataDirectory, new GsonJson());
        ReleaseVersion kept = ReleaseVersion.parse("3.0.2");
        Files.createDirectories(files.versionDirectory(ReleaseVersion.parse("3.0.1")));
        Files.createDirectories(files.versionDirectory(kept));

        files.deleteVersionsExcept(Set.of(kept));

        assertFalse(Files.exists(files.versionDirectory(ReleaseVersion.parse("3.0.1"))));
        assertTrue(Files.exists(files.versionDirectory(kept)));
    }
}
