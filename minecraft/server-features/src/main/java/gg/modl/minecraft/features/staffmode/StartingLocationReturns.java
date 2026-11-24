package gg.modl.minecraft.features.staffmode;

import gg.modl.minecraft.core.util.Futures;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.features.FeatureScheduler;
import gg.modl.minecraft.features.TeleportOutcome;
import org.jspecify.annotations.Nullable;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

final class StartingLocationReturns {
    static final int MAX_PENDING = 500;
    static final long EXPIRY_MILLIS = TimeUnit.DAYS.toMillis(30);

    private static final long REFUSAL_CHECK_DELAY_TICKS = 1;

    private final StaffModeOps ops;
    private final Predicate<UUID> holdsBelongings;
    private final FeatureScheduler scheduler;
    private final StartingLocationStore store;
    private final PluginLogger logger;
    private final LongSupplier clock;
    private final Map<UUID, PendingReturn> pendingReturns = new LinkedHashMap<>();

    private volatile boolean stopping;
    private boolean loaded;

    StartingLocationReturns(StaffModeOps ops, Predicate<UUID> holdsBelongings, FeatureScheduler scheduler,
                            StartingLocationStore store, PluginLogger logger, LongSupplier clock) {
        this.ops = ops;
        this.holdsBelongings = holdsBelongings;
        this.scheduler = scheduler;
        this.store = store;
        this.logger = logger;
        this.clock = clock;
    }

    void load(Runnable whenLoaded) {
        Futures.whenSucceeded(store.load(), stored -> {
            if (stopping) return;
            scheduler.runOnMainThread(() -> {
                if (stopping) return;
                merge(stored);
                whenLoaded.run();
            });
        }, logger);
    }

    private synchronized void merge(Map<UUID, PendingReturn> stored) {
        Map<UUID, PendingReturn> recordedBeforeLoading = new LinkedHashMap<>(pendingReturns);
        pendingReturns.clear();
        long now = clock.getAsLong();
        stored.forEach((player, pending) -> pendingReturns.put(player,
                new PendingReturn(pending.getLocation(), Math.min(pending.getRecordedAt(), now))));
        recordedBeforeLoading.forEach((player, pending) -> {
            PendingReturn earlier = pendingReturns.get(player);
            if (earlier == null || hasExpired(earlier) || !ops.canReturnTo(earlier.getLocation())) {
                pendingReturns.put(player, pending);
            }
        });
        loaded = true;
        removeExpiredAndExcess();
        if (!pendingReturns.equals(stored)) save();
    }

    synchronized void remember(UUID player, StartingLocation location) {
        PendingReturn earlier = pendingReturns.get(player);
        if (earlier != null && !hasExpired(earlier) && ops.canReturnTo(earlier.getLocation())) return;
        pendingReturns.remove(player);
        pendingReturns.put(player, new PendingReturn(location, clock.getAsLong()));
        removeExpiredAndExcess();
        save();
    }

    synchronized boolean isPending(UUID player) {
        PendingReturn pending = pendingReturns.get(player);
        if (pending == null) return false;
        if (!hasExpired(pending) || holdsBelongings.test(player)) return true;
        forget(player, pending);
        return false;
    }

    void returnPlayer(UUID player) {
        PendingReturn pending = pendingFor(player);
        if (pending == null || !ops.isOnline(player)) return;
        if (!ops.canReturnTo(pending.getLocation())) {
            logger.debug("[staff-mode] The world where staff mode started for " + player
                    + " is not loaded; the return waits for a later join");
            return;
        }
        Futures.whenSucceeded(ops.teleportToStartingLocation(player, pending.getLocation()),
                outcome -> settle(player, pending, outcome), logger);
    }

    void stop() {
        stopping = true;
    }

    void close() {
        store.close();
    }

    private void settle(UUID player, PendingReturn pending, TeleportOutcome outcome) {
        if (outcome == TeleportOutcome.MOVED && !holdsBelongings.test(player)) {
            forget(player, pending);
        } else if (outcome == TeleportOutcome.REFUSED && !stopping) {
            scheduler.runForPlayerLater(player, () -> abandonUnlessDeparted(player, pending), REFUSAL_CHECK_DELAY_TICKS);
        }
    }

    private void abandonUnlessDeparted(UUID player, PendingReturn pending) {
        if (!ops.isOnline(player) || holdsBelongings.test(player)) return;
        if (forget(player, pending)) ops.reportRefusedReturn(player);
    }

    private synchronized @Nullable PendingReturn pendingFor(UUID player) {
        return pendingReturns.get(player);
    }

    private synchronized boolean forget(UUID player, PendingReturn pending) {
        if (!pendingReturns.remove(player, pending)) return false;
        save();
        return true;
    }

    private void removeExpiredAndExcess() {
        pendingReturns.entrySet().removeIf(entry -> hasExpired(entry.getValue()) && !holdsBelongings.test(entry.getKey()));
        Iterator<UUID> oldestFirst = pendingReturns.keySet().iterator();
        while (pendingReturns.size() > MAX_PENDING && oldestFirst.hasNext()) {
            if (!holdsBelongings.test(oldestFirst.next())) oldestFirst.remove();
        }
    }

    private boolean hasExpired(PendingReturn pending) {
        return clock.getAsLong() - pending.getRecordedAt() > EXPIRY_MILLIS;
    }

    private void save() {
        if (!loaded) return;
        store.save(new LinkedHashMap<>(pendingReturns));
    }
}
