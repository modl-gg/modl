package gg.modl.minecraft.features.staffmode.custody;

import gg.modl.minecraft.core.util.Futures;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.features.FeatureScheduler;
import gg.modl.minecraft.features.staffmode.custody.CustodySession.Phase;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class StaffCustody {
    static final int MAX_SNAPSHOT_ATTEMPTS = 10;

    private static final String PREFIX = "[staff-mode] ";

    private final BelongingsPort port;
    private final CustodyStore store;
    private final FeatureScheduler scheduler;
    private final PluginLogger logger;
    private final CustodyListener listener;
    private final Map<UUID, CustodySession> sessions = new ConcurrentHashMap<>();

    private volatile boolean stopped;

    public StaffCustody(BelongingsPort port, CustodyStore store, FeatureScheduler scheduler, PluginLogger logger,
                        CustodyListener listener) {
        this.port = port;
        this.store = store;
        this.scheduler = scheduler;
        this.logger = logger;
        this.listener = listener;
    }

    public void playerJoined(UUID player) {
        inspect(player, true);
    }

    public void playerFoundOnline(UUID player) {
        if (!sessions.containsKey(player)) inspect(player, false);
    }

    public void playerLeft(UUID player) {
        release(player);
        sessions.remove(player);
    }

    public boolean holdsBelongings(UUID player) {
        CustodySession session = sessions.get(player);
        return session != null && session.holdsBelongings();
    }

    public boolean hasTakenBelongings(UUID player) {
        CustodySession session = sessions.get(player);
        return session != null && session.phase == Phase.HELD;
    }

    public Set<UUID> playersInCustody() {
        Set<UUID> players = new HashSet<>();
        sessions.forEach((player, session) -> {
            if (session.holdsBelongings() || session.phase == Phase.SAVING) players.add(player);
        });
        return players;
    }

    public void hold(UUID player) {
        CustodySession session = sessions.get(player);
        if (session == null) {
            inspect(player, false);
            return;
        }
        switch (session.phase) {
            case HELD:
                listener.belongingsTaken(player);
                break;
            case STRANDED:
            case BLOCKED:
                listener.staffModeRefused(player, CustodyNotice.UNRESOLVED);
                break;
            case FREE:
                if (!port.isAlive(player)) return;
                session.attempts = 0;
                snapshot(player, session);
                break;
            default:
                break;
        }
    }

    public boolean release(UUID player) {
        CustodySession session = sessions.get(player);
        if (session == null) return true;
        switch (session.phase) {
            case SAVING:
                cancelSnapshot(player, session);
                return true;
            case HELD:
                return port.isAlive(player) && giveBack(player, session);
            default:
                return !session.holdsBelongings();
        }
    }

    public void stop() {
        stopped = true;
    }

    public void close() {
        sessions.clear();
        store.close();
    }

    private void inspect(UUID player, boolean loadedFromDisk) {
        CustodySession session = new CustodySession(loadedFromDisk);
        sessions.put(player, session);
        try {
            session.mark = port.custodyMark(player);
        } catch (RuntimeException | LinkageError failure) {
            logger.warning(PREFIX + "Could not read the custody mark of " + player
                    + "; staff mode is off for them until they rejoin", failure);
            session.phase = Phase.BLOCKED;
            listener.custodyResolved(player);
            return;
        }
        whenDone(player, session, store.load(player),
                stored -> resolveStored(player, session, stored),
                failure -> resolveUnreadable(player, session, failure));
    }

    private void resolveUnreadable(UUID player, CustodySession session, Throwable failure) {
        apply(player, session, CustodyResolution.ofUnreadable(session.mark, session.loadedFromDisk), failure);
    }

    private void resolveStored(UUID player, CustodySession session, Optional<CustodyRecord> stored) {
        if (stored.isPresent()) {
            CustodyRecord record = stored.get();
            session.record = record;
            session.id = record.getId();
        }
        apply(player, session, CustodyResolution.ofRecord(session.mark, stored, session.loadedFromDisk,
                port.markKind(), () -> port.hasSavedPlayerData(player)), null);
    }

    private void apply(UUID player, CustodySession session, CustodyResolution resolution,
                       @Nullable Throwable failure) {
        switch (resolution) {
            case NOTHING_HELD:
                settle(player, session, Phase.FREE);
                break;
            case STILL_HELD:
                adopt(player, session);
                listener.custodyResolved(player);
                break;
            case RESTORE_LOST_PLAYER_DATA:
                if (adopt(player, session) && port.isAlive(player)) giveBack(player, session);
                listener.custodyResolved(player);
                break;
            case OBSOLETE:
                removeObsolete(player, session);
                break;
            case SET_ASIDE:
                setAside(player, session, "is not marked in their saved player data, which keeps its own inventory", null);
                break;
            case SET_UNREADABLE_ASIDE:
                setAside(player, session, "cannot be read and is not marked in their saved player data", failure);
                break;
            case MARK_WITHOUT_RECORD:
                forgetMarkWithoutRecord(player, session);
                break;
            case WRONG_RECORD:
                strand(player, session, "it belongs to a different staff-mode session", null);
                listener.custodyResolved(player);
                break;
            case UNREADABLE_WHILE_HELD:
                strand(player, session, "it cannot be read", failure);
                listener.custodyResolved(player);
                break;
            case UNRESOLVED:
                logger.warning(PREFIX + "Could not read " + store.describe(player)
                        + "; it is kept and staff mode is off for " + player + " until they rejoin", failure);
                settle(player, session, Phase.BLOCKED);
                break;
        }
    }

    private void settle(UUID player, CustodySession session, Phase phase) {
        session.phase = phase;
        listener.custodyResolved(player);
    }

    private boolean adopt(UUID player, CustodySession session) {
        CustodyRecord record = heldRecord(player, session);
        try {
            session.belongings = port.decode(record.getBelongings());
            session.phase = Phase.HELD;
            return true;
        } catch (BelongingsException | RuntimeException | LinkageError failure) {
            strand(player, session, "its items cannot be read on this server", failure);
            return false;
        }
    }

    private void strand(UUID player, CustodySession session, String reason, @Nullable Throwable failure) {
        session.phase = Phase.STRANDED;
        CustodyRecord record = session.record;
        String gameMode = record == null ? null : record.getGameMode();
        logger.severe(PREFIX + "The belongings of " + player + " stay in " + store.describe(player) + " because "
                + reason + describeCause(failure) + ". It is never deleted. Fix the cause and have them rejoin, "
                + "or move the file away to let them play with what they carry now.");
        leaveCreative(player, gameMode);
        listener.notifyPlayer(player, CustodyNotice.HELD);
    }

    private void removeObsolete(UUID player, CustodySession session) {
        whenDone(player, session, store.remove(player), removed -> {
            session.id = null;
            settle(player, session, Phase.FREE);
        }, failure -> {
            logger.warning(PREFIX + "Could not delete the completed custody record " + store.describe(player), failure);
            settle(player, session, Phase.FREE);
        });
    }

    private void setAside(UUID player, CustodySession session, String reason, @Nullable Throwable readFailure) {
        CustodyRecord record = session.record;
        if (record != null && !record.isReleased()) leaveCreative(player, record.getGameMode());
        whenDone(player, session, store.setAside(player), kept -> {
            logger.warning(PREFIX + "The custody record of " + player + " " + reason + describeCause(readFailure)
                    + "; nothing was restored and the record is kept as " + kept);
            session.id = null;
            settle(player, session, Phase.FREE);
        }, failure -> {
            logger.warning(PREFIX + "Could not set " + store.describe(player)
                    + " aside; it is kept and staff mode is off for " + player + " until they rejoin", failure);
            settle(player, session, Phase.BLOCKED);
        });
    }

    private void forgetMarkWithoutRecord(UUID player, CustodySession session) {
        logger.severe(PREFIX + player + " joined in staff-mode state but " + store.describe(player)
                + " does not exist; they keep what they carry and leave creative.");
        leaveCreative(player, null);
        try {
            port.clearMark(player);
        } catch (RuntimeException | LinkageError failure) {
            logger.warning(PREFIX + "Could not clear the custody mark of " + player, failure);
            settle(player, session, Phase.BLOCKED);
            return;
        }
        session.mark = Optional.empty();
        listener.notifyPlayer(player, CustodyNotice.RECORD_MISSING);
        settle(player, session, Phase.FREE);
    }

    private void leaveCreative(UUID player, @Nullable String gameMode) {
        try {
            port.leaveCreative(player, gameMode);
        } catch (RuntimeException | LinkageError failure) {
            logger.warning(PREFIX + "Could not take " + player + " out of creative", failure);
        }
    }

    private void snapshot(UUID player, CustodySession session) {
        Belongings captured;
        try {
            if (port.custodyMark(player).isPresent()) {
                refuse(player, session, CustodyNotice.UNRESOLVED, "their player data is still marked as in custody", null);
                return;
            }
            captured = port.capture(player);
        } catch (BelongingsException | RuntimeException | LinkageError failure) {
            if (session.phase == Phase.SAVING) cancelSnapshot(player, session);
            refuse(player, session, CustodyNotice.NOT_SAVED, "their belongings cannot be captured", failure);
            return;
        }
        UUID id = session.id;
        if (id == null) {
            id = UUID.randomUUID();
            session.id = id;
        }
        UUID custodyId = id;
        CustodyRecord record = CustodyRecord.held(custodyId, port.markKind(), captured);
        session.attempts++;
        session.belongings = captured;
        session.record = record;
        session.phase = Phase.SAVING;
        Object write = new Object();
        session.write = write;
        whenDone(player, session, store.save(player, record),
                written -> afterSave(player, session, write, custodyId, captured, null),
                failure -> afterSave(player, session, write, custodyId, captured, failure));
    }

    private void afterSave(UUID player, CustodySession session, Object write, UUID custodyId, Belongings captured,
                           @Nullable Throwable failure) {
        if (session.phase != Phase.SAVING || session.write != write) return;
        if (failure != null) {
            if (session.attempts > 1) cancelSnapshot(player, session);
            session.phase = Phase.FREE;
            session.belongings = null;
            refuse(player, session, CustodyNotice.NOT_SAVED, "their belongings could not be written to "
                    + store.describe(player), failure);
            return;
        }
        try {
            if (!port.isAlive(player)) {
                cancelSnapshot(player, session);
            } else {
                afterComparison(player, session, custodyId, port.isUnchanged(player, captured));
            }
        } catch (RuntimeException | LinkageError checkFailure) {
            cancelSnapshot(player, session);
            refuse(player, session, CustodyNotice.NOT_SAVED, "their belongings could not be compared", checkFailure);
        }
    }

    private void afterComparison(UUID player, CustodySession session, UUID custodyId, boolean unchanged) {
        if (session.phase != Phase.SAVING || sessions.get(player) != session) return;
        if (unchanged) {
            takeAway(player, session, custodyId);
        } else if (session.attempts < MAX_SNAPSHOT_ATTEMPTS) {
            snapshot(player, session);
        } else {
            cancelSnapshot(player, session);
            refuse(player, session, CustodyNotice.KEPT_CHANGING, "their inventory kept changing while it was saved", null);
        }
    }

    private void takeAway(UUID player, CustodySession session, UUID custodyId) {
        try {
            port.takeAway(player, custodyId);
        } catch (BelongingsException | RuntimeException | LinkageError failure) {
            if (mayBeMarked(player)) session.phase = Phase.HELD;
            else cancelSnapshot(player, session);
            refuse(player, session, CustodyNotice.NOT_SAVED, "their belongings could not be taken into custody", failure);
            return;
        }
        session.phase = Phase.HELD;
        session.refusalLogged = false;
        listener.belongingsTaken(player);
    }

    private boolean mayBeMarked(UUID player) {
        try {
            return port.custodyMark(player).isPresent();
        } catch (RuntimeException | LinkageError unknown) {
            logger.warning(PREFIX + "Could not check the custody mark of " + player, unknown);
            return true;
        }
    }

    private void refuse(UUID player, CustodySession session, CustodyNotice notice, String reason,
                        @Nullable Throwable failure) {
        if (!session.refusalLogged) {
            session.refusalLogged = true;
            logger.warning(PREFIX + "Staff mode did not start for " + player + " because " + reason, failure);
        }
        listener.staffModeRefused(player, notice);
    }

    private void cancelSnapshot(UUID player, CustodySession session) {
        session.phase = Phase.FREE;
        session.write = null;
        session.belongings = null;
        saveRelease(player, session);
    }

    private boolean giveBack(UUID player, CustodySession session) {
        Belongings belongings = session.belongings;
        if (belongings == null) throw new IllegalStateException("the held custody session of " + player + " has no belongings");
        try {
            port.giveBack(player, belongings);
        } catch (BelongingsException | RuntimeException | LinkageError failure) {
            if (!session.returnFailureLogged) {
                session.returnFailureLogged = true;
                logger.severe(PREFIX + "Could not return the belongings of " + player + describeCause(failure)
                        + "; they stay in " + store.describe(player) + " and are tried again when they rejoin");
                listener.notifyPlayer(player, CustodyNotice.HELD);
            }
            return false;
        }
        session.phase = Phase.FREE;
        session.mark = Optional.empty();
        session.belongings = null;
        session.returnFailureLogged = false;
        saveRelease(player, session);
        return true;
    }

    private void saveRelease(UUID player, CustodySession session) {
        Futures.whenComplete(store.save(player, heldRecord(player, session).release()), (saved, failure) -> {
            if (failure != null) {
                logger.warning(PREFIX + "Could not record that the belongings of " + player + " were returned in "
                        + store.describe(player), failure);
            }
        }, logger);
    }

    private static CustodyRecord heldRecord(UUID player, CustodySession session) {
        CustodyRecord record = session.record;
        if (record == null) throw new IllegalStateException("the custody session of " + player + " has no record");
        return record;
    }

    private <T extends @Nullable Object> void whenDone(UUID player, CustodySession session, CompletableFuture<T> work,
                                                       Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        Futures.whenComplete(work, (value, failure) -> {
            if (stopped) return;
            scheduler.runForPlayer(player, () -> {
                if (stopped || sessions.get(player) != session) return;
                if (failure != null) onFailure.accept(failure);
                else onSuccess.accept(value);
            });
        }, logger);
    }

    private static String describeCause(@Nullable Throwable failure) {
        if (failure == null) return "";
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause == failure ? " (" + failure + ")" : " (" + failure + ": " + cause + ")";
    }
}
