package gg.modl.minecraft.spigot.features;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalReplayFilesTest {
    private static final long NOW = TimeUnit.DAYS.toMillis(30);
    private static final long TTL = TimeUnit.DAYS.toMillis(7);

    @TempDir
    Path replays;

    @Test
    void deletesOnlyReplayFilesOlderThanTheirTtl() throws IOException {
        File expired = replay("replay-1.modlreplay", NOW - TTL - 1);
        File fresh = replay("replay-2.modlreplay", NOW - TTL + 1);

        assertEquals(1, LocalReplayFiles.deleteOlderThan(replays.toFile(), TTL, NOW));

        assertFalse(expired.exists());
        assertTrue(fresh.exists());
    }

    @Test
    void aMissingReplayDirectoryIsNothingToClean() {
        assertEquals(0, LocalReplayFiles.deleteOlderThan(replays.resolve("never-created").toFile(), TTL, NOW));
    }

    private File replay(String name, long lastModified) throws IOException {
        File file = Files.write(replays.resolve(name), new byte[]{1}).toFile();
        assertTrue(file.setLastModified(lastModified));
        return file;
    }
}
