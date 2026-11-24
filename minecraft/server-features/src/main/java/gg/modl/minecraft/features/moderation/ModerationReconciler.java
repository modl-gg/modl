package gg.modl.minecraft.features.moderation;

import gg.modl.minecraft.core.network.Freeze;
import gg.modl.minecraft.core.network.ModerationSnapshot;
import gg.modl.minecraft.core.network.ModerationSnapshotListener;
import gg.modl.minecraft.core.network.ModerationState;
import gg.modl.minecraft.core.network.StaffSessionState;
import gg.modl.minecraft.core.util.PluginLogger;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;

public final class ModerationReconciler implements ModerationSnapshotListener {
    private static final String THREAD_NAME = "modl-reconcile";

    private final LocalModerationOps ops;
    private final Executor executor;
    private final Runnable shutdown;
    private final LongSupplier nanoClock;
    private final PluginLogger logger;
    private final Map<String, SourceState> sources = new HashMap<>();
    private final Set<UUID> onlinePlayers = new HashSet<>();
    private boolean linkUp;

    ModerationReconciler(LocalModerationOps ops, Executor executor, Runnable shutdown, LongSupplier nanoClock,
                         PluginLogger logger) {
        this.ops = ops;
        this.executor = executor;
        this.shutdown = shutdown;
        this.nanoClock = nanoClock;
        this.logger = logger;
    }

    public static ModerationReconciler start(LocalModerationOps ops, PluginLogger logger) {
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
        return new ModerationReconciler(ops, executor, executor::shutdownNow, System::nanoTime, logger);
    }

    @Override
    public void accept(String sourceId, ModerationSnapshot snapshot) {
        if (sourceId == null || snapshot == null || snapshot.getEpoch() == null || snapshot.getState() == null) return;
        post(() -> acceptSnapshot(sourceId, snapshot));
    }

    @Override
    public void retire(String sourceId) {
        post(() -> {
            if (sources.remove(sourceId) != null) reconcileOnlinePlayers();
        });
    }

    public void onJoin(UUID uuid) {
        post(() -> {
            onlinePlayers.add(uuid);
            reconcile(uuid, desiredState());
        });
    }

    public void onQuit(UUID uuid) {
        post(() -> forgetPlayer(uuid));
    }

    @Override
    public void linkUp() {
        post(() -> linkUp = true);
    }

    @Override
    public void linkDown() {
        post(() -> linkUp = false);
    }

    public void stop() {
        shutdown.run();
    }

    private void acceptSnapshot(String sourceId, ModerationSnapshot snapshot) {
        SourceState current = sources.get(sourceId);
        long receivedAtNanos = nanoClock.getAsLong();
        if (current != null && current.epoch.equals(snapshot.getEpoch())) {
            if (snapshot.getRevision() < current.revision) return;
            if (snapshot.getRevision() == current.revision) receivedAtNanos = current.receivedAtNanos;
        }
        sources.put(sourceId, new SourceState(snapshot.getEpoch(), snapshot.getRevision(), receivedAtNanos,
                snapshot.getState()));
        reconcileOnlinePlayers();
    }

    private void forgetPlayer(UUID uuid) {
        onlinePlayers.remove(uuid);
        if (linkUp) return;
        boolean pruned = false;
        for (Map.Entry<String, SourceState> entry : sources.entrySet()) {
            SourceState prunedState = entry.getValue().without(uuid);
            if (prunedState != entry.getValue()) {
                entry.setValue(prunedState);
                pruned = true;
            }
        }
        if (pruned) reconcileOnlinePlayers();
    }

    private void reconcileOnlinePlayers() {
        Map<UUID, DesiredModeration> desired = desiredState();
        Set<UUID> players = new HashSet<>(desired.keySet());
        players.addAll(ops.moderatedPlayers());
        players.retainAll(onlinePlayers);
        for (UUID uuid : players) reconcile(uuid, desired);
    }

    private void reconcile(UUID uuid, Map<UUID, DesiredModeration> desiredState) {
        DesiredModeration desired = desiredState.get(uuid);
        if (desired == null) desired = DesiredModeration.NOTHING;
        try {
            reconcileFreeze(uuid, desired);
            reconcileStaffMode(uuid, desired);
            reconcileVanish(uuid, desired);
            reconcileTarget(uuid, desired);
        } catch (RuntimeException failure) {
            logger.warning("[server-features] Failed to apply the moderation state of " + uuid + ": " + failure);
        }
    }

    private void reconcileFreeze(UUID uuid, DesiredModeration desired) {
        UUID frozenBy = desired.frozenBy;
        boolean frozen = ops.isFrozen(uuid);
        if (frozenBy != null && !frozen) ops.freeze(uuid, frozenBy);
        else if (frozenBy == null && frozen) ops.unfreeze(uuid);
    }

    private void reconcileStaffMode(UUID uuid, DesiredModeration desired) {
        boolean inStaffMode = ops.isInStaffMode(uuid);
        if (desired.staffMode && !inStaffMode) ops.enterStaffMode(uuid);
        else if (!desired.staffMode && (inStaffMode || ops.hasUnreportedRefusal(uuid))) ops.exitStaffMode(uuid);
    }

    private void reconcileVanish(UUID uuid, DesiredModeration desired) {
        if (desired.vanished != ops.isVanished(uuid)) ops.applyVanish(uuid, desired.vanished);
    }

    private void reconcileTarget(UUID uuid, DesiredModeration desired) {
        UUID target = desired.target;
        if (Objects.equals(target, ops.targetOf(uuid))) return;
        if (target == null) ops.clearTarget(uuid);
        else ops.setTarget(uuid, target);
    }

    private Map<UUID, DesiredModeration> desiredState() {
        List<SourceState> newestFirst = new ArrayList<>(sources.values());
        newestFirst.sort((first, second) -> Long.compare(second.receivedAtNanos, first.receivedAtNanos));
        Map<UUID, DesiredModeration> desired = new HashMap<>();
        for (SourceState source : newestFirst) {
            for (Map.Entry<UUID, Freeze> entry : source.state.getFrozen().entrySet()) {
                desired.computeIfAbsent(entry.getKey(), uuid -> new DesiredModeration())
                        .freezeBy(entry.getValue().getStaff());
            }
            for (Map.Entry<UUID, StaffSessionState> entry : source.state.getStaff().entrySet()) {
                desired.computeIfAbsent(entry.getKey(), uuid -> new DesiredModeration())
                        .include(entry.getValue());
            }
        }
        return desired;
    }

    private void post(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException stopped) {
            logger.debug("[server-features] Ignored a moderation update after the reconciler stopped");
        }
    }

    private static final class DesiredModeration {
        private static final DesiredModeration NOTHING = new DesiredModeration();

        private @Nullable UUID frozenBy;
        private boolean staffMode;
        private boolean vanished;
        private @Nullable UUID target;

        private void freezeBy(UUID staff) {
            if (frozenBy == null) frozenBy = staff;
        }

        private void include(StaffSessionState session) {
            staffMode |= session.isStaffMode();
            vanished |= session.isVanished();
            if (target == null && session.hasTarget()) target = session.getTarget();
        }
    }

    private static final class SourceState {
        private final String epoch;
        private final long revision;
        private final long receivedAtNanos;
        private final ModerationState state;

        private SourceState(String epoch, long revision, long receivedAtNanos, ModerationState state) {
            this.epoch = epoch;
            this.revision = revision;
            this.receivedAtNanos = receivedAtNanos;
            this.state = state;
        }

        private SourceState without(UUID player) {
            boolean changed = state.getFrozen().containsKey(player);
            Map<UUID, Freeze> frozen = new LinkedHashMap<>(state.getFrozen());
            frozen.remove(player);
            Map<UUID, StaffSessionState> staff = new LinkedHashMap<>();
            for (Map.Entry<UUID, StaffSessionState> entry : state.getStaff().entrySet()) {
                StaffSessionState session = entry.getValue();
                if (entry.getKey().equals(player)) {
                    changed = true;
                } else if (player.equals(session.getTarget())) {
                    changed = true;
                    if (session.isStaffMode() || session.isVanished()) {
                        staff.put(entry.getKey(), new StaffSessionState(session.isStaffMode(), session.isVanished(), null));
                    }
                } else {
                    staff.put(entry.getKey(), session);
                }
            }
            return changed
                    ? new SourceState(epoch, revision, receivedAtNanos, new ModerationState(frozen, staff))
                    : this;
        }
    }
}
