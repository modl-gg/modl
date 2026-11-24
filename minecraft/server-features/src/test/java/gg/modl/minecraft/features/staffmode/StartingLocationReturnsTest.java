package gg.modl.minecraft.features.staffmode;

import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.features.TeleportOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartingLocationReturnsTest {
    private final FakeStaffModeOps ops = new FakeStaffModeOps();
    private final InMemoryStartingLocationStore store = new InMemoryStartingLocationStore();
    private final AtomicLong now = new AtomicLong(StartingLocationReturns.EXPIRY_MILLIS * 3);
    private final StartingLocationReturns returns = new StartingLocationReturns(ops, ops::holdsBelongings,
            new ImmediateFeatureScheduler(ops), store, PluginLogger.fromJul(Logger.getAnonymousLogger()), now::get);
    private final UUID staff = UUID.randomUUID();

    @Test
    void theEntryIsDeletedOnlyOnceTheTeleportSucceeded() {
        remember("base");
        CompletableFuture<TeleportOutcome> teleport = new CompletableFuture<>();
        ops.teleportResult = teleport;

        returns.returnPlayer(staff);
        assertTrue(store.saved.containsKey(staff));
        teleport.complete(TeleportOutcome.MOVED);

        assertFalse(store.saved.containsKey(staff));
    }

    @Test
    void aFailedTeleportKeepsTheEntryForTheNextJoin() {
        remember("base");
        ops.teleportResult = CompletableFuture.completedFuture(TeleportOutcome.FAILED);

        returns.returnPlayer(staff);

        assertTrue(store.saved.containsKey(staff));
        assertTrue(ops.refusedReturns.isEmpty());
    }

    @Test
    void aRefusalWhileOnlineIsReportedAndGivenUp() {
        remember("base");
        ops.teleportResult = CompletableFuture.completedFuture(TeleportOutcome.REFUSED);

        returns.returnPlayer(staff);

        assertFalse(store.saved.containsKey(staff));
        assertEquals(Collections.singletonList(staff), ops.refusedReturns);
    }

    @Test
    void aRefusalBecauseThePlayerLeftKeepsTheEntry() {
        remember("base");
        CompletableFuture<TeleportOutcome> teleport = new CompletableFuture<>();
        ops.teleportResult = teleport;

        returns.returnPlayer(staff);
        ops.online.remove(staff);
        teleport.complete(TeleportOutcome.REFUSED);

        assertTrue(store.saved.containsKey(staff));
        assertTrue(ops.refusedReturns.isEmpty());
    }

    @Test
    void anUnloadedWorldWaitsWithoutTeleporting() {
        remember("arena");
        ops.unloadedPlaces.add("arena");

        returns.returnPlayer(staff);
        assertTrue(ops.returnedToStart.isEmpty());
        assertTrue(store.saved.containsKey(staff));

        ops.unloadedPlaces.clear();
        returns.returnPlayer(staff);

        assertEquals(Collections.singletonList("arena"), ops.returnedToStart);
        assertFalse(store.saved.containsKey(staff));
    }

    @Test
    void aNewSessionReplacesAPlaceWhoseWorldIsGone() {
        remember("arena");
        ops.unloadedPlaces.add("arena");

        remember("base");
        returns.returnPlayer(staff);

        assertEquals(Collections.singletonList("base"), ops.returnedToStart);
    }

    @Test
    void theFirstPlaceWinsWhileItCanStillBeReached() {
        remember("base");
        remember("suspect");
        returns.returnPlayer(staff);

        assertEquals(Collections.singletonList("base"), ops.returnedToStart);
    }

    @Test
    void anExpiredEntryIsDroppedInsteadOfReturned() {
        remember("base");
        now.addAndGet(StartingLocationReturns.EXPIRY_MILLIS + 1);

        assertFalse(returns.isPending(staff));
        assertTrue(store.saved.isEmpty());
    }

    @Test
    void staffStillInStaffModeDoNotExpire() {
        remember("base");
        ops.snapshots.put(staff, UUID.randomUUID());
        now.addAndGet(StartingLocationReturns.EXPIRY_MILLIS + 1);

        assertTrue(returns.isPending(staff));
    }

    @Test
    void loadingDropsExpiredEntriesAndTheOldestBeyondTheCap() {
        Map<UUID, PendingReturn> stored = new LinkedHashMap<>();
        UUID expired = new UUID(0, 1);
        UUID oldest = new UUID(0, 2);
        stored.put(expired, new PendingReturn(ops.place("base"), now.get() - StartingLocationReturns.EXPIRY_MILLIS - 1));
        stored.put(oldest, new PendingReturn(ops.place("base"), now.get() - 10));
        for (int i = 0; i < StartingLocationReturns.MAX_PENDING; i++) {
            stored.put(new UUID(1, i), new PendingReturn(ops.place("base"), now.get()));
        }
        store.saved = stored;

        returns.load(() -> { });

        assertEquals(StartingLocationReturns.MAX_PENDING, store.saved.size());
        assertFalse(store.saved.containsKey(expired));
        assertFalse(store.saved.containsKey(oldest));
    }

    @Test
    void theCapSparesStaffStillInStaffMode() {
        remember("base");
        ops.snapshots.put(staff, UUID.randomUUID());
        for (int i = 0; i < StartingLocationReturns.MAX_PENDING; i++) {
            returns.remember(new UUID(2, i), ops.place("base"));
        }

        assertEquals(StartingLocationReturns.MAX_PENDING, store.saved.size());
        assertTrue(store.saved.containsKey(staff));
        assertFalse(store.saved.containsKey(new UUID(2, 0)));
    }

    @Test
    void aTimeFromTheFutureCannotOutliveTheExpiry() {
        store.saved = Collections.singletonMap(staff, new PendingReturn(ops.place("base"), Long.MAX_VALUE));

        returns.load(() -> { });

        assertEquals(now.get(), store.saved.get(staff).getRecordedAt());
    }

    @Test
    void nothingIsWrittenBeforeTheFileHasBeenRead() {
        StartingLocationReturns unloaded = new StartingLocationReturns(ops, ops::holdsBelongings,
                new ImmediateFeatureScheduler(ops), store, PluginLogger.fromJul(Logger.getAnonymousLogger()), now::get);
        store.saved = Collections.singletonMap(staff, new PendingReturn(ops.place("base"), now.get()));
        UUID other = UUID.randomUUID();

        unloaded.remember(other, ops.place("arena"));
        assertEquals(Collections.singleton(staff), store.saved.keySet());
        unloaded.load(() -> { });

        assertEquals(2, store.saved.size());
    }

    @BeforeEach
    void loadTheEmptyFile() {
        returns.load(() -> { });
    }

    private void remember(String place) {
        ops.online.add(staff);
        returns.remember(staff, ops.place(place));
    }
}
