package gg.modl.minecraft.features.moderation;

import gg.modl.minecraft.core.network.Freeze;
import gg.modl.minecraft.core.network.ModerationSnapshot;
import gg.modl.minecraft.core.network.ModerationState;
import gg.modl.minecraft.core.network.StaffSessionState;
import gg.modl.minecraft.core.util.PluginLogger;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModerationReconcilerTest {
    private static final PluginLogger SILENT = new PluginLogger() {
        @Override public void info(String message) {}
        @Override public void warning(String message) {}
        @Override public void severe(String message) {}
    };

    private final ManualExecutor executor = new ManualExecutor();
    private final AtomicLong clock = new AtomicLong();
    private final RecordingOps ops = new RecordingOps();
    private final ModerationReconciler reconciler =
            new ModerationReconciler(ops, executor, () -> {}, clock::get, SILENT);

    private final UUID staff = UUID.randomUUID();
    private final UUID player = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @Test
    void anUnreportedRefusalIsReleasedOnceTheCoreEndsStaffMode() {
        join(staff);
        ops.refused.add(staff);
        reconciler.accept("hub", snapshot("a", 1, frozen(), staffMode(staff)));
        executor.runAll();
        assertTrue(ops.operations.contains("enter " + staff));
        ops.staffMode.remove(staff);
        ops.operations.clear();

        reconciler.accept("hub", snapshot("a", 2, frozen(), staffSessions()));
        executor.runAll();

        assertTrue(ops.operations.contains("exit " + staff), ops.operations.toString());
    }

    @Test
    void desiredStateIsTheUnionOfAllSources() {
        join(staff, player);
        reconciler.accept("first", snapshot("a", 1, frozen(player), staffMode(staff)));
        reconciler.accept("second", snapshot("b", 1, frozen(), session(staff, false, true, null)));
        executor.runAll();

        assertTrue(ops.isFrozen(player));
        assertTrue(ops.isInStaffMode(staff));
        assertTrue(ops.isVanished(staff));
    }

    @Test
    void aStaleRevisionInTheSameEpochIsIgnored() {
        join(player);
        reconciler.accept("proxy", snapshot("a", 2, frozen(player), staffSessions()));
        reconciler.accept("proxy", snapshot("a", 1, frozen(), staffSessions()));
        executor.runAll();

        assertTrue(ops.isFrozen(player));
        assertEquals(Collections.singletonList("freeze " + player), ops.operations);
    }

    @Test
    void aNewEpochReplacesTheSourceEvenWithALowerRevision() {
        join(player);
        reconciler.accept("proxy", snapshot("a", 9, frozen(player), staffSessions()));
        reconciler.accept("proxy", snapshot("b", 1, frozen(), staffSessions()));
        executor.runAll();

        assertEquals(Arrays.asList("freeze " + player, "unfreeze " + player), ops.operations);
    }

    @Test
    void retiringASourceDropsItsState() {
        join(player);
        reconciler.accept("proxy", snapshot("a", 1, frozen(player), staffSessions()));
        reconciler.retire("proxy");
        executor.runAll();

        assertEquals(Arrays.asList("freeze " + player, "unfreeze " + player), ops.operations);
    }

    @Test
    void offlinePlayersAreSkippedUntilTheyJoin() {
        reconciler.accept("proxy", snapshot("a", 1, frozen(player), staffMode(staff)));
        executor.runAll();

        assertTrue(ops.operations.isEmpty());

        reconciler.onJoin(player);
        executor.runAll();

        assertEquals(Collections.singletonList("freeze " + player), ops.operations);
    }

    @Test
    void aRepeatedSnapshotProducesNoOperations() {
        join(player, staff);
        ModerationSnapshot snapshot = snapshot("a", 3, frozen(player), session(staff, true, true, player));
        reconciler.accept("proxy", snapshot);
        executor.runAll();
        int applied = ops.operations.size();

        reconciler.accept("proxy", snapshot);
        executor.runAll();

        assertEquals(applied, ops.operations.size());
    }

    @Test
    void linkLossKeepsTheLastStateForPlayersWhoJoinLater() {
        join(player);
        reconciler.linkUp();
        reconciler.accept("proxy", snapshot("a", 1, frozen(player, other), staffSessions()));
        reconciler.linkDown();
        executor.runAll();

        reconciler.onJoin(other);
        executor.runAll();

        assertTrue(ops.isFrozen(player));
        assertTrue(ops.isFrozen(other));
    }

    @Test
    void aQuitWhileTheLinkIsDownPrunesThePlayer() {
        join(player);
        reconciler.accept("proxy", snapshot("a", 1, frozen(player), staffSessions()));
        executor.runAll();

        quitAndRejoin(player);

        assertFalse(ops.isFrozen(player));
    }

    @Test
    void aQuitWhileTheLinkIsUpKeepsThePlayerForTheCore() {
        join(player);
        reconciler.linkUp();
        reconciler.accept("proxy", snapshot("a", 1, frozen(player), staffSessions()));
        executor.runAll();

        quitAndRejoin(player);

        assertTrue(ops.isFrozen(player));
    }

    @Test
    void aPrunedPlayerIsClearedAsAnotherStaffMembersTarget() {
        join(staff, player);
        reconciler.accept("proxy", snapshot("a", 1, frozen(), session(staff, true, false, player)));
        executor.runAll();

        reconciler.onQuit(player);
        executor.runAll();

        assertNull(ops.targetOf(staff));
        assertTrue(ops.isInStaffMode(staff));
    }

    @Test
    void theTargetComesFromTheMostRecentlyReceivedSource() {
        join(staff);
        clock.set(10);
        reconciler.accept("older", snapshot("a", 1, frozen(), session(staff, true, false, player)));
        executor.runAll();
        clock.set(20);
        reconciler.accept("newer", snapshot("b", 1, frozen(), session(staff, true, false, other)));
        executor.runAll();

        assertEquals(other, ops.targetOf(staff));

        clock.set(30);
        reconciler.accept("older", snapshot("a", 2, frozen(), session(staff, true, false, player)));
        executor.runAll();

        assertEquals(player, ops.targetOf(staff));
    }

    @Test
    void aRepublishWithTheSameRevisionDoesNotChangeTargetPriority() {
        join(staff);
        clock.set(10);
        ModerationSnapshot older = snapshot("a", 1, frozen(), session(staff, true, false, player));
        reconciler.accept("older", older);
        clock.set(20);
        reconciler.accept("newer", snapshot("b", 1, frozen(), session(staff, true, false, other)));
        clock.set(30);
        reconciler.accept("older", older);
        executor.runAll();

        assertEquals(other, ops.targetOf(staff));
    }

    @Test
    void stateLeavingTheSnapshotIsUndone() {
        join(staff);
        reconciler.accept("proxy", snapshot("a", 1, frozen(), session(staff, true, true, player)));
        reconciler.accept("proxy", snapshot("a", 2, frozen(), staffSessions()));
        executor.runAll();

        assertFalse(ops.isInStaffMode(staff));
        assertFalse(ops.isVanished(staff));
        assertNull(ops.targetOf(staff));
    }

    @Test
    void nothingRunsOutsideTheReconcilerExecutor() {
        reconciler.onJoin(player);
        reconciler.accept("proxy", snapshot("a", 1, frozen(player), staffSessions()));
        reconciler.retire("other");
        reconciler.onQuit(staff);

        assertTrue(ops.operations.isEmpty());
        assertEquals(4, executor.pending());
    }

    @Test
    void operationsRunOnTheReconcileThread() throws InterruptedException {
        ThreadRecordingOps threadOps = new ThreadRecordingOps();
        ModerationReconciler started = ModerationReconciler.start(threadOps, SILENT);
        try {
            started.onJoin(player);
            started.accept("proxy", snapshot("a", 1, frozen(player), staffSessions()));

            assertTrue(threadOps.firstFreeze.await(5, TimeUnit.SECONDS));
            assertEquals(Collections.singletonList("modl-reconcile"), threadOps.threads);
        } finally {
            started.stop();
        }
    }

    private void join(UUID... players) {
        for (UUID uuid : players) reconciler.onJoin(uuid);
        executor.runAll();
    }

    private void quitAndRejoin(UUID uuid) {
        ops.releaseLocally(uuid);
        reconciler.onQuit(uuid);
        executor.runAll();
        reconciler.onJoin(uuid);
        executor.runAll();
    }

    private ModerationSnapshot snapshot(String epoch, long revision, Map<UUID, Freeze> frozen,
                                        Map<UUID, StaffSessionState> staff) {
        return new ModerationSnapshot(epoch, revision, new ModerationState(frozen, staff));
    }

    private Map<UUID, Freeze> frozen(UUID... players) {
        Map<UUID, Freeze> frozen = new LinkedHashMap<>();
        for (UUID uuid : players) frozen.put(uuid, new Freeze(staff, 1L));
        return frozen;
    }

    private static Map<UUID, StaffSessionState> staffSessions() {
        return Collections.emptyMap();
    }

    private static Map<UUID, StaffSessionState> staffMode(UUID uuid) {
        return session(uuid, true, false, null);
    }

    private static Map<UUID, StaffSessionState> session(UUID uuid, boolean staffMode, boolean vanished, UUID target) {
        return Collections.singletonMap(uuid, new StaffSessionState(staffMode, vanished, target));
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        int pending() {
            return tasks.size();
        }

        void runAll() {
            Runnable task;
            while ((task = tasks.poll()) != null) task.run();
        }
    }

    private static class RecordingOps implements LocalModerationOps {
        final List<String> operations = new ArrayList<>();
        private final Set<UUID> frozen = new HashSet<>();
        final Set<UUID> staffMode = new HashSet<>();
        final Set<UUID> refused = new HashSet<>();
        private final Set<UUID> vanished = new HashSet<>();
        private final Map<UUID, UUID> targets = new HashMap<>();

        void releaseLocally(UUID uuid) {
            frozen.remove(uuid);
            staffMode.remove(uuid);
            vanished.remove(uuid);
            targets.remove(uuid);
        }

        @Override
        public Set<UUID> moderatedPlayers() {
            Set<UUID> players = new HashSet<>(frozen);
            players.addAll(staffMode);
            players.addAll(vanished);
            players.addAll(targets.keySet());
            players.addAll(refused);
            return players;
        }

        @Override
        public boolean isFrozen(UUID uuid) {
            return frozen.contains(uuid);
        }

        @Override
        public void freeze(UUID target, UUID staff) {
            operations.add("freeze " + target);
            frozen.add(target);
        }

        @Override
        public void unfreeze(UUID target) {
            operations.add("unfreeze " + target);
            frozen.remove(target);
        }

        @Override
        public boolean isInStaffMode(UUID uuid) {
            return staffMode.contains(uuid);
        }

        @Override
        public boolean hasUnreportedRefusal(UUID uuid) {
            return refused.contains(uuid);
        }

        @Override
        public void enterStaffMode(UUID uuid) {
            operations.add("enter " + uuid);
            staffMode.add(uuid);
        }

        @Override
        public void exitStaffMode(UUID uuid) {
            operations.add("exit " + uuid);
            staffMode.remove(uuid);
            targets.remove(uuid);
        }

        @Override
        public boolean isVanished(UUID uuid) {
            return vanished.contains(uuid);
        }

        @Override
        public void applyVanish(UUID uuid, boolean vanish) {
            operations.add("vanish " + uuid + " " + vanish);
            if (vanish) vanished.add(uuid);
            else vanished.remove(uuid);
        }

        @Override
        public UUID targetOf(UUID staff) {
            return targets.get(staff);
        }

        @Override
        public void setTarget(UUID staff, UUID target) {
            operations.add("target " + staff + " " + target);
            targets.put(staff, target);
        }

        @Override
        public void clearTarget(UUID staff) {
            operations.add("clear target " + staff);
            targets.remove(staff);
        }
    }

    private static final class ThreadRecordingOps extends RecordingOps {
        private final List<String> threads = new CopyOnWriteArrayList<>();
        private final CountDownLatch firstFreeze = new CountDownLatch(1);

        @Override
        public void freeze(UUID target, UUID staff) {
            threads.add(Thread.currentThread().getName());
            super.freeze(target, staff);
            firstFreeze.countDown();
        }
    }
}
