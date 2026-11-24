package gg.modl.minecraft.core.network;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

public final class ModerationSessionService {
    public static final int MAX_FROZEN_PLAYERS = 256;
    public static final int MAX_STAFF_SESSIONS = 256;

    private final Object lock = new Object();
    private final String epoch;
    private final LongSupplier clock;
    private final Map<UUID, Freeze> frozen = new HashMap<>();
    private final Map<UUID, StaffSessionState> sessions = new HashMap<>();
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();
    private long revision;

    public ModerationSessionService() {
        this(System::currentTimeMillis, randomEpoch());
    }

    ModerationSessionService(LongSupplier clock, String epoch) {
        this.clock = clock;
        this.epoch = epoch;
    }

    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    public ModerationChange freeze(UUID target, UUID staff) {
        ModerationChange change;
        synchronized (lock) {
            if (frozen.containsKey(target)) {
                change = ModerationChange.UNCHANGED;
            } else if (frozen.size() >= MAX_FROZEN_PLAYERS) {
                change = ModerationChange.LIMIT_REACHED;
            } else {
                frozen.put(target, new Freeze(staff, clock.getAsLong()));
                change = bumpLocked();
            }
        }
        return announce(change);
    }

    public ModerationChange unfreeze(UUID target) {
        ModerationChange change;
        synchronized (lock) {
            change = frozen.remove(target) != null ? bumpLocked() : ModerationChange.UNCHANGED;
        }
        return announce(change);
    }

    public ModerationChange enterStaffMode(UUID staff, boolean vanish) {
        ModerationChange change;
        synchronized (lock) {
            StaffSessionState current = sessions.get(staff);
            if (current != null && current.isStaffMode()) {
                change = ModerationChange.UNCHANGED;
            } else if (current == null && sessions.size() >= MAX_STAFF_SESSIONS) {
                change = ModerationChange.LIMIT_REACHED;
            } else {
                boolean vanished = vanish || (current != null && current.isVanished());
                sessions.put(staff, new StaffSessionState(true, vanished, null));
                change = bumpLocked();
            }
        }
        return announce(change);
    }

    public ModerationChange exitStaffMode(UUID staff) {
        ModerationChange change;
        synchronized (lock) {
            StaffSessionState current = sessions.get(staff);
            if (current == null || !current.isStaffMode()) {
                change = ModerationChange.UNCHANGED;
            } else {
                sessions.remove(staff);
                change = bumpLocked();
            }
        }
        return announce(change);
    }

    public ModerationChange setVanished(UUID staff, boolean vanished) {
        ModerationChange change;
        synchronized (lock) {
            StaffSessionState current = sessions.get(staff);
            if (current == null) {
                change = vanished ? startVanishedSessionLocked(staff) : ModerationChange.UNCHANGED;
            } else if (current.isVanished() == vanished) {
                change = ModerationChange.UNCHANGED;
            } else {
                storeLocked(staff, current.withVanished(vanished));
                change = bumpLocked();
            }
        }
        return announce(change);
    }

    public ModerationChange setTarget(UUID staff, UUID target) {
        ModerationChange change;
        synchronized (lock) {
            StaffSessionState current = sessions.get(staff);
            if (current == null || !current.isStaffMode()) {
                change = ModerationChange.NOT_IN_STAFF_MODE;
            } else if (target.equals(current.getTarget())) {
                change = ModerationChange.UNCHANGED;
            } else {
                sessions.put(staff, current.withTarget(target));
                change = bumpLocked();
            }
        }
        return announce(change);
    }

    public ModerationChange clearTarget(UUID staff) {
        ModerationChange change;
        synchronized (lock) {
            StaffSessionState current = sessions.get(staff);
            if (current == null || !current.hasTarget()) {
                change = ModerationChange.UNCHANGED;
            } else {
                storeLocked(staff, current.withTarget(null));
                change = bumpLocked();
            }
        }
        return announce(change);
    }

    public LeftNetworkOutcome playerLeftNetwork(UUID player) {
        LeftNetworkOutcome outcome;
        synchronized (lock) {
            boolean wasFrozen = frozen.remove(player) != null;
            StaffSessionState session = sessions.remove(player);
            List<UUID> staffWhoseTargetLeft = clearTargetsPointingAtLocked(player);
            outcome = new LeftNetworkOutcome(wasFrozen, session != null && session.isVanished(),
                    session != null && session.isStaffMode(), staffWhoseTargetLeft);
            if (outcome.changedAnything()) bumpLocked();
        }
        if (outcome.changedAnything()) notifyChanged();
        return outcome;
    }

    public boolean isFrozen(UUID player) {
        synchronized (lock) {
            return frozen.containsKey(player);
        }
    }

    public boolean isVanished(UUID staff) {
        synchronized (lock) {
            StaffSessionState session = sessions.get(staff);
            return session != null && session.isVanished();
        }
    }

    public boolean isInStaffMode(UUID staff) {
        synchronized (lock) {
            StaffSessionState session = sessions.get(staff);
            return session != null && session.isStaffMode();
        }
    }

    public Set<UUID> staffWithSessions() {
        synchronized (lock) {
            return new HashSet<>(sessions.keySet());
        }
    }

    public @Nullable UUID targetOf(UUID staff) {
        synchronized (lock) {
            StaffSessionState session = sessions.get(staff);
            return session != null ? session.getTarget() : null;
        }
    }

    public ModerationSnapshot snapshot() {
        synchronized (lock) {
            return new ModerationSnapshot(epoch, revision, new ModerationState(frozen, sessions));
        }
    }

    private ModerationChange startVanishedSessionLocked(UUID staff) {
        if (sessions.size() >= MAX_STAFF_SESSIONS) return ModerationChange.LIMIT_REACHED;
        sessions.put(staff, new StaffSessionState(false, true, null));
        return bumpLocked();
    }

    private void storeLocked(UUID staff, StaffSessionState state) {
        if (state.isEmpty()) sessions.remove(staff);
        else sessions.put(staff, state);
    }

    private List<UUID> clearTargetsPointingAtLocked(UUID player) {
        Map<UUID, StaffSessionState> retargeted = new LinkedHashMap<>();
        for (Map.Entry<UUID, StaffSessionState> entry : sessions.entrySet()) {
            if (player.equals(entry.getValue().getTarget())) retargeted.put(entry.getKey(), entry.getValue().withTarget(null));
        }
        retargeted.forEach(this::storeLocked);
        return new ArrayList<>(retargeted.keySet());
    }

    private ModerationChange bumpLocked() {
        revision++;
        return ModerationChange.CHANGED;
    }

    private ModerationChange announce(ModerationChange change) {
        if (change.changed()) notifyChanged();
        return change;
    }

    private void notifyChanged() {
        for (Runnable listener : changeListeners) listener.run();
    }

    private static String randomEpoch() {
        return String.format("%016x", ThreadLocalRandom.current().nextLong());
    }
}
