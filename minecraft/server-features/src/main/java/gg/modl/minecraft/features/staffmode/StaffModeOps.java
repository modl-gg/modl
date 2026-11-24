package gg.modl.minecraft.features.staffmode;

import gg.modl.minecraft.features.TeleportOutcome;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface StaffModeOps {
    boolean isOnline(UUID uuid);

    @Nullable String playerName(UUID uuid);

    Set<UUID> onlinePlayerUuids();

    int onlinePlayerCount();

    int maxPlayerCount();

    int playerPing(UUID uuid);

    double playerHealth(UUID uuid);

    void clearInventory(UUID uuid);

    void setGameMode(UUID uuid, StaffGameMode mode);

    void setHotbarSlot(UUID uuid, int slot, String materialId, String displayName, List<String> lore);

    Optional<StartingLocation> currentLocation(UUID uuid);

    boolean canReturnTo(StartingLocation location);

    CompletableFuture<TeleportOutcome> teleportToStartingLocation(UUID uuid, StartingLocation location);

    void reportRefusedReturn(UUID uuid);

    void hidePlayer(UUID viewerUuid, UUID hiddenUuid);

    void showPlayer(UUID viewerUuid, UUID shownUuid);

    void teleportToPlayer(UUID uuid, UUID targetUuid);

    void createScoreboard(UUID uuid, ScoreboardContent content);

    void updateScoreboard(UUID uuid, ScoreboardContent content);

    void removeScoreboard(UUID uuid);

    void discardScoreboard(UUID uuid);

    void clearScoreboards();

    void openTargetInventory(UUID viewerUuid, UUID targetUuid);

    void sendMessage(UUID uuid, String message);
}
