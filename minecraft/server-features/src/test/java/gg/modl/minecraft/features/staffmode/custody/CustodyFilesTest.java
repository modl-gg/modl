package gg.modl.minecraft.features.staffmode.custody;

import gg.modl.minecraft.core.util.PluginLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustodyFilesTest {
    @TempDir
    Path dataFolder;

    private final UUID player = UUID.randomUUID();
    private final CustodyRecord record = new CustodyRecord(UUID.randomUUID(), false, "persistent-data", "SURVIVAL",
            "1.21.11\nSURVIVAL\nitems with § and 中文\n-\npaper:AAECAw==");

    private CustodyFiles files;

    @BeforeEach
    void open() {
        files = new CustodyFiles(dataFolder, PluginLogger.fromJul(Logger.getAnonymousLogger()));
    }

    @AfterEach
    void close() {
        files.close();
    }

    @Test
    void aRecordSurvivesANewInstanceExactly() {
        files.save(player, record).join();
        files.close();
        open();

        assertEquals(Optional.of(record), files.load(player).join());
    }

    @Test
    void aReleaseReplacesTheRecordAtomically() {
        files.save(player, record).join();
        files.save(player, record.release()).join();

        assertTrue(files.load(player).join().get().isReleased());
        assertFalse(Files.exists(recordFile().resolveSibling(player + CustodyFiles.RECORD_SUFFIX + ".tmp")));
    }

    @Test
    void noFileMeansNoRecord() {
        assertEquals(Optional.empty(), files.load(player).join());
    }

    @Test
    void aTornRecordIsReportedAndLeftInPlace() throws IOException {
        files.save(player, record).join();
        byte[] whole = Files.readAllBytes(recordFile());
        byte[] torn = new byte[whole.length - 3];
        System.arraycopy(whole, 0, torn, 0, torn.length);
        Files.write(recordFile(), torn);

        assertThrows(CompletionException.class, () -> files.load(player).join());
        assertEquals(torn.length, Files.size(recordFile()));
    }

    @Test
    void anEditedRecordIsRefused() throws IOException {
        files.save(player, record).join();
        String text = new String(Files.readAllBytes(recordFile()), StandardCharsets.UTF_8);
        Files.write(recordFile(), text.replace("\nHELD\n", "\nRELEASED\n").getBytes(StandardCharsets.UTF_8));

        assertThrows(CompletionException.class, () -> files.load(player).join());
    }

    @Test
    void aFailedWriteLeavesTheEarlierRecord() throws IOException {
        files.save(player, record).join();
        Files.createDirectory(recordFile().resolveSibling(player + CustodyFiles.RECORD_SUFFIX + ".tmp"));

        assertThrows(CompletionException.class, () -> files.save(player, record.release()).join());
        assertEquals(Optional.of(record), files.load(player).join());
    }

    @Test
    void settingAsideKeepsEveryByte() throws IOException {
        files.save(player, record).join();
        byte[] original = Files.readAllBytes(recordFile());

        String kept = files.setAside(player).join();

        assertEquals(Optional.empty(), files.load(player).join());
        assertEquals(new String(original, StandardCharsets.UTF_8),
                new String(Files.readAllBytes(dataFolder.getFileSystem().getPath(kept)), StandardCharsets.UTF_8));
    }

    @Test
    void recordsHaveNoCap() throws IOException {
        List<CompletableFuture<Void>> saves = new ArrayList<>();
        for (int i = 0; i < 600; i++) saves.add(files.save(new UUID(0, i), record));
        CompletableFuture.allOf(saves.toArray(new CompletableFuture<?>[0])).join();
        files.close();

        try (Stream<Path> stored = Files.list(dataFolder.resolve(CustodyFiles.DIRECTORY))) {
            assertEquals(600, stored.count());
        }
    }

    @Test
    void aClosedStoreFailsInsteadOfDroppingTheWriteSilently() {
        files.close();

        assertThrows(CompletionException.class, () -> files.save(player, record).join());
    }

    private Path recordFile() {
        return dataFolder.resolve(CustodyFiles.DIRECTORY).resolve(player + CustodyFiles.RECORD_SUFFIX);
    }
}
