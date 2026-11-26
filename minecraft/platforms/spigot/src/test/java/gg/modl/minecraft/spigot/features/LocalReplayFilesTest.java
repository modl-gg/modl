package gg.modl.minecraft.spigot.features;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LocalReplayFilesTest {
    private static final long NOW = TimeUnit.DAYS.toMillis(30);

    @TempDir
    Path replays;

    @Test
    void leftoversOlderThanADayAndPartialFilesOlderThanAnHourAreDeleted() throws IOException {
        File expired = replay("replay-1.modlreplay", NOW - LocalReplayFiles.LEFTOVER_TTL_MILLIS - 1, 1);
        File fresh = replay("replay-2.modlreplay", NOW - LocalReplayFiles.LEFTOVER_TTL_MILLIS + 1, 1);
        File abandoned = replay("replay-3.partial", NOW - LocalReplayFiles.PARTIAL_TTL_MILLIS - 1, 1);
        File writing = replay("replay-4.partial", NOW - 1_000, 1);

        assertEquals(2, new LocalReplayFiles(replays).sweep(NOW));

        assertFalse(expired.exists());
        assertTrue(fresh.exists());
        assertFalse(abandoned.exists());
        assertTrue(writing.exists());
    }

    @Test
    void theOldestFilesGoFirstOnceTheFolderOutgrowsItsCap() throws IOException {
        long half = LocalReplayFiles.MAX_TOTAL_BYTES / 2;
        long settled = NOW - LocalReplayFiles.PARTIAL_TTL_MILLIS - 60_000;
        File oldest = replay("a.modlreplay", settled - 2_000, half);
        File middle = replay("b.modlreplay", settled - 1_000, half);
        File newest = replay("c.modlreplay", settled, half);

        assertEquals(1, new LocalReplayFiles(replays).sweep(NOW));

        assertFalse(oldest.exists());
        assertTrue(middle.exists());
        assertTrue(newest.exists());
    }

    @Test
    void theCapNeverDeletesAFileThatIsStillBeingWrittenOrUploaded() throws IOException {
        long half = LocalReplayFiles.MAX_TOTAL_BYTES / 2;
        File first = replay("a.partial", NOW - 3_000, half);
        File second = replay("b.modlreplay", NOW - 2_000, half);
        File third = replay("c.modlreplay", NOW - 1_000, half);

        assertEquals(0, new LocalReplayFiles(replays).sweep(NOW));

        assertTrue(first.exists() && second.exists() && third.exists());
    }

    @Test
    void aMissingReplayDirectoryIsNothingToClean() {
        assertEquals(0, new LocalReplayFiles(replays.resolve("never-created")).sweep(NOW));
    }

    @Test
    void replayFilesAndTheirFolderAreOwnerOnly() throws IOException {
        assumeTrue(Files.getFileStore(replays).supportsFileAttributeView(PosixFileAttributeView.class));
        LocalReplayFiles files = new LocalReplayFiles(replays.resolve("replays"));
        Path partial = files.directory().resolve("one.partial");
        try (OutputStream out = files.create(partial)) {
            out.write(1);
        }

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(files.directory())));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(partial)));
    }

    private File replay(String name, long lastModified, long size) throws IOException {
        File file = replays.resolve(name).toFile();
        try (RandomAccessFile sparse = new RandomAccessFile(file, "rw")) {
            sparse.setLength(size);
        }
        assertTrue(file.setLastModified(lastModified));
        return file;
    }
}
