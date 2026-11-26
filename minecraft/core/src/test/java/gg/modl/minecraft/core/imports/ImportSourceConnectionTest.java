package gg.modl.minecraft.core.imports;

import gg.modl.minecraft.core.imports.source.ImportSource;
import gg.modl.minecraft.core.imports.source.PlayerRecords;
import gg.modl.minecraft.core.imports.source.SourceException;
import gg.modl.minecraft.core.imports.source.SourceInventory;
import gg.modl.minecraft.core.imports.source.SourcePlayer;
import gg.modl.minecraft.core.imports.source.SourceReader;
import gg.modl.minecraft.core.imports.sql.BatchedPlayers;
import gg.modl.minecraft.core.imports.sql.FakeSqlConnection;
import gg.modl.minecraft.core.imports.sql.SqlSession;
import gg.modl.minecraft.core.support.MapLocaleManager;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportSourceConnectionTest {
    private static final long WAIT_MILLIS = 10_000;
    private static final long PROMPTLY_MILLIS = 3000;
    private static final int PLAYERS = 2;
    private static final int ROWS_BEFORE_CANCEL = 20;

    @TempDir
    Path dataFolder;

    private final FakeImportBackend backend = new FakeImportBackend();
    private final List<String> console = new CopyOnWriteArrayList<>();
    private final List<FakeSqlConnection> opened = new CopyOnWriteArrayList<>();
    private volatile Opener opener = FakeSqlConnection::empty;
    private volatile boolean refusesToOpen;
    private Imports imports;

    @AfterEach
    void stopImports() {
        if (imports != null) imports.shutdown();
    }

    @Test
    void aConnectionThatDiedAfterThePreviewIsOpenedAgainOnceAndTheImportRuns() throws Exception {
        imports = imports();
        previewed();
        opened.get(0).dies();

        confirmed();
        await(() -> console.contains("import.completed"));

        assertEquals(2, opened.size());
        assertEquals(1, opened.get(0).closes());
        assertEquals(1, backend.starts().size());
        assertEquals(1, backend.uploads().size());
    }

    @Test
    void aLiveConnectionFromThePreviewIsReused() throws Exception {
        imports = imports();
        previewed();

        confirmed();
        await(() -> console.contains("import.completed"));

        assertEquals(1, opened.size());
        assertEquals(Collections.singletonList(2), opened.get(0).validationTimeouts());
    }

    @Test
    void aConnectionThatCannotBeOpenedAgainEndsWithoutStartingAJob() throws Exception {
        imports = imports();
        previewed();
        opened.get(0).dies();
        refusesToOpen = true;

        imports.confirm("slow", "widen", "keep");
        await(() -> console.contains("import.source_reconnect_failed"));

        assertTrue(backend.starts().isEmpty());
        assertEquals(1, opened.get(0).closes());
    }

    @Test
    void cancelDuringALongBatchEndsTheJobPromptly() throws Exception {
        opener = () -> FakeSqlConnection.endless(1, false);
        imports = imports();
        previewed();
        confirmed();
        FakeSqlConnection running = opened.get(0);
        await(() -> running.rows() >= ROWS_BEFORE_CANCEL);

        long cancelledAt = System.nanoTime();
        imports.cancel();
        await(() -> console.contains("import.ended.cancelled") && running.closes() == 1);

        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelledAt) < PROMPTLY_MILLIS);
        assertTrue(backend.ends().stream().allMatch(end -> ImportEnding.CANCELLED.wireName().equals(end.getReason())),
                backend.ends().toString());
        assertFalse(backend.ends().isEmpty());
        assertEquals(1, running.prepared());
        assertEquals(Collections.singletonList("modl-import-monitor"), running.cancellingThreads());
        assertTrue(backend.uploads().isEmpty());
    }

    private void previewed() throws InterruptedException {
        imports.preview("slow");
        await(() -> console.contains("import.preview.confirm"));
    }

    private void confirmed() throws InterruptedException {
        imports.confirm("slow", "widen", "keep");
        await(() -> !backend.starts().isEmpty());
        assertRightAfterThePreview(console);
    }

    static void assertRightAfterThePreview(List<String> console) {
        assertFalse(console.contains("import.busy"), console.toString());
        assertFalse(console.contains("import.confirm_first"), console.toString());
    }

    private Imports imports() {
        return Imports.builder()
                .sources(new ImportSources(Collections.singletonList(new SlowSqlSource())))
                .backend(backend)
                .localeManager(new MapLocaleManager().withFallback(path -> path))
                .console(console::add)
                .logger(new RecordingPluginLogger())
                .dataFolder(dataFolder)
                .serverName(() -> "proxy")
                .build();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.sleep(20);
        }
    }

    @FunctionalInterface
    private interface Opener {
        FakeSqlConnection open();
    }

    private final class SlowSqlSource implements ImportSource {
        @Override
        public String id() {
            return "slow";
        }

        @Override
        public String displayName() {
            return "Slow";
        }

        @Override
        public boolean isPresent() {
            return true;
        }

        @Override
        public SourceReader open() throws SourceException {
            if (refusesToOpen) throw SourceException.failed("Connection refused");
            FakeSqlConnection fake = opener.open();
            opened.add(fake);
            try {
                return new SlowReader(fake.session());
            } catch (SQLException failure) {
                throw SourceException.failed(failure);
            }
        }
    }

    private static final class SlowReader implements SourceReader {
        private final SqlSession session;

        private SlowReader(SqlSession session) {
            this.session = session;
        }

        @Override
        public SourceInventory inventory() {
            return SourceInventory.builder().players(PLAYERS).bans(PLAYERS).build();
        }

        @Override
        public long historyEntriesBefore(long epochMillis) {
            return 0;
        }

        @Override
        public PlayerRecords players() {
            List<String> uuids = new ArrayList<>();
            for (int index = 1; index <= PLAYERS; index++) uuids.add("1111111" + index + "-1111-4111-8111-111111111111");
            return new BatchedPlayers(BatchedPlayers.Keys.of(uuids), batch -> {
                session.query("SELECT * FROM punishments", row -> { });
                Map<String, SourcePlayer> loaded = new HashMap<>();
                for (String uuid : batch) {
                    loaded.put(uuid, new SourcePlayer(uuid,
                            Collections.singletonList(new SourcePlayer.Username("Player" + uuid.charAt(7), 1L)),
                            Collections.emptyList(), Collections.emptyList()));
                }
                return loaded;
            }, () -> session.query("SELECT 1 FROM punishments WHERE 1 = 0", row -> { }));
        }

        @Override
        public boolean isReusable() {
            return session.isReusable();
        }

        @Override
        public void cancelRunning() {
            session.cancelRunning();
        }

        @Override
        public void close() {
            session.close();
        }
    }
}
