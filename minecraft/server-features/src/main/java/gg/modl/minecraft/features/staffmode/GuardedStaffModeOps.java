package gg.modl.minecraft.features.staffmode;

import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.features.TeleportOutcome;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

final class GuardedStaffModeOps implements StaffModeOps {
    private final StaffModeOps delegate;
    private final PluginLogger logger;
    private final Set<UUID> reportedScoreboardFailures = ConcurrentHashMap.newKeySet();

    GuardedStaffModeOps(StaffModeOps delegate, PluginLogger logger) {
        this.delegate = delegate;
        this.logger = logger;
    }

    @Override
    public CompletableFuture<TeleportOutcome> teleportToStartingLocation(UUID uuid, StartingLocation location) {
        try {
            return delegate.teleportToStartingLocation(uuid, location);
        } catch (RuntimeException | LinkageError e) {
            logger.warning("[staff-mode] Failed to return " + uuid + " to where staff mode started", e);
            return CompletableFuture.completedFuture(TeleportOutcome.FAILED);
        }
    }

    @Override
    public void createScoreboard(UUID uuid, ScoreboardContent content) {
        try {
            delegate.createScoreboard(uuid, content);
        } catch (RuntimeException | LinkageError e) {
            logger.warning("[staff-mode] Failed to create the scoreboard of " + uuid, e);
        }
    }

    @Override
    public void updateScoreboard(UUID uuid, ScoreboardContent content) {
        try {
            delegate.updateScoreboard(uuid, content);
            reportedScoreboardFailures.remove(uuid);
        } catch (RuntimeException | LinkageError e) {
            if (reportedScoreboardFailures.add(uuid)) {
                logger.warning("[staff-mode] Failed to update the scoreboard of " + uuid, e);
            }
        }
    }

    @Override
    public void removeScoreboard(UUID uuid) {
        try {
            delegate.removeScoreboard(uuid);
        } catch (RuntimeException | LinkageError e) {
            logger.warning("[staff-mode] Failed to remove the scoreboard of " + uuid, e);
        }
    }

    @Override
    public void hidePlayer(UUID viewerUuid, UUID hiddenUuid) {
        try {
            delegate.hidePlayer(viewerUuid, hiddenUuid);
        } catch (RuntimeException | LinkageError e) {
            logger.warning("[staff-mode] Failed to hide " + hiddenUuid + " from " + viewerUuid, e);
        }
    }

    @Override
    public void showPlayer(UUID viewerUuid, UUID shownUuid) {
        try {
            delegate.showPlayer(viewerUuid, shownUuid);
        } catch (RuntimeException | LinkageError e) {
            logger.warning("[staff-mode] Failed to show " + shownUuid + " to " + viewerUuid, e);
        }
    }

    @Override
    public boolean isOnline(UUID uuid) {
        return delegate.isOnline(uuid);
    }

    @Override
    public @Nullable String playerName(UUID uuid) {
        return delegate.playerName(uuid);
    }

    @Override
    public Set<UUID> onlinePlayerUuids() {
        return delegate.onlinePlayerUuids();
    }

    @Override
    public int onlinePlayerCount() {
        return delegate.onlinePlayerCount();
    }

    @Override
    public int maxPlayerCount() {
        return delegate.maxPlayerCount();
    }

    @Override
    public int playerPing(UUID uuid) {
        return delegate.playerPing(uuid);
    }

    @Override
    public double playerHealth(UUID uuid) {
        return delegate.playerHealth(uuid);
    }

    @Override
    public void clearInventory(UUID uuid) {
        delegate.clearInventory(uuid);
    }

    @Override
    public void setGameMode(UUID uuid, StaffGameMode mode) {
        delegate.setGameMode(uuid, mode);
    }

    @Override
    public void setHotbarSlot(UUID uuid, int slot, String materialId, String displayName, List<String> lore) {
        delegate.setHotbarSlot(uuid, slot, materialId, displayName, lore);
    }

    @Override
    public Optional<StartingLocation> currentLocation(UUID uuid) {
        return delegate.currentLocation(uuid);
    }

    @Override
    public boolean canReturnTo(StartingLocation location) {
        return delegate.canReturnTo(location);
    }

    @Override
    public void reportRefusedReturn(UUID uuid) {
        delegate.reportRefusedReturn(uuid);
    }

    @Override
    public void teleportToPlayer(UUID uuid, UUID targetUuid) {
        delegate.teleportToPlayer(uuid, targetUuid);
    }

    @Override
    public void discardScoreboard(UUID uuid) {
        reportedScoreboardFailures.remove(uuid);
        delegate.discardScoreboard(uuid);
    }

    @Override
    public void clearScoreboards() {
        reportedScoreboardFailures.clear();
        delegate.clearScoreboards();
    }

    @Override
    public void openTargetInventory(UUID viewerUuid, UUID targetUuid) {
        delegate.openTargetInventory(viewerUuid, targetUuid);
    }

    @Override
    public void sendMessage(UUID uuid, String message) {
        delegate.sendMessage(uuid, message);
    }
}
