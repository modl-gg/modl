package gg.modl.minecraft.core.imports.sql;

import gg.modl.minecraft.core.imports.source.SourceException;
import gg.modl.minecraft.core.imports.source.SourcePlayer;
import gg.modl.minecraft.core.imports.source.UnreadablePlayerException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlSessionCancelTest {
    private static final long PROMPTLY_MILLIS = 2000;
    private static final int ROWS_BEFORE_CANCEL = 20;

    @Test
    void aLiveConnectionIsCheckedWithABoundedTimeout() throws SQLException {
        FakeSqlConnection fake = FakeSqlConnection.empty();
        try (SqlSession session = fake.session()) {
            assertTrue(session.isReusable());
            fake.dies();
            assertFalse(session.isReusable());
        }
        assertEquals(Arrays.asList(2, 2), fake.validationTimeouts());
    }

    @Test
    void aConnectionThatFailsItsCheckCountsAsDead() throws SQLException {
        FakeSqlConnection fake = FakeSqlConnection.empty();
        fake.failsValidation();
        try (SqlSession session = fake.session()) {
            assertFalse(session.isReusable());
        }
    }

    @Test
    void aCancelledSessionIsNeverReused() throws SQLException {
        FakeSqlConnection fake = FakeSqlConnection.empty();
        try (SqlSession session = fake.session()) {
            session.cancelRunning();
            assertFalse(session.isReusable());
            assertThrows(SQLException.class, () -> session.query("SELECT 1", row -> { }));
        }
        assertTrue(fake.validationTimeouts().isEmpty());
        assertEquals(0, fake.prepared());
    }

    @Test
    void cancelStopsABatchBetweenRowsWhenTheDriverIgnoresTheCancel() throws Exception {
        assertCancelEndsTheBatchPromptly(FakeSqlConnection.endless(1, false));
    }

    @Test
    void cancelEndsTheBatchWithoutReadingEachPlayerAlone() throws Exception {
        assertCancelEndsTheBatchPromptly(FakeSqlConnection.endless(1, true));
    }

    private static void assertCancelEndsTheBatchPromptly(FakeSqlConnection fake) throws Exception {
        try (SqlSession session = fake.session()) {
            List<String> players = Arrays.asList("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222");
            BatchedPlayers records = new BatchedPlayers(BatchedPlayers.Keys.of(players), uuids -> {
                session.query("SELECT * FROM history WHERE uuid IN (?, ?)", row -> { });
                Map<String, SourcePlayer> loaded = new HashMap<>();
                for (String uuid : uuids) {
                    loaded.put(uuid, new SourcePlayer(uuid, Collections.emptyList(), Collections.emptyList(), Collections.emptyList()));
                }
                return loaded;
            }, () -> session.query("SELECT 1 FROM history WHERE 1 = 0", row -> { }));
            CompletableFuture<SourcePlayer> reading = CompletableFuture.supplyAsync(() -> {
                try {
                    return records.next();
                } catch (SourceException | UnreadablePlayerException | RuntimeException failure) {
                    throw new IllegalStateException(failure);
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (fake.rows() < ROWS_BEFORE_CANCEL) {
                assertTrue(System.nanoTime() < deadline, "the batch never started");
                Thread.sleep(5);
            }

            long cancelledAt = System.nanoTime();
            session.cancelRunning();
            ExecutionException ended = assertThrows(ExecutionException.class,
                    () -> reading.get(PROMPTLY_MILLIS, TimeUnit.MILLISECONDS));

            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelledAt) < PROMPTLY_MILLIS);
            assertInstanceOf(SourceException.class, ended.getCause().getCause());
            assertEquals(1, fake.prepared());
            assertEquals(1, fake.cancellingThreads().size());
        }
    }
}
