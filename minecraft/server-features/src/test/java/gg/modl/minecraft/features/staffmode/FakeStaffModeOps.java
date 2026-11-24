package gg.modl.minecraft.features.staffmode;

import gg.modl.minecraft.features.TeleportOutcome;
import gg.modl.minecraft.features.staffmode.custody.Belongings;
import gg.modl.minecraft.features.staffmode.custody.BelongingsException;
import gg.modl.minecraft.features.staffmode.custody.BelongingsPort;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

class FakeStaffModeOps implements StaffModeOps, BelongingsPort {
    static final String SPAWN = "spawn";

    final Set<UUID> online = ConcurrentHashMap.newKeySet();
    final Map<UUID, String> names = new HashMap<>();
    final Map<UUID, UUID> snapshots = new ConcurrentHashMap<>();
    final Map<UUID, String> positions = new HashMap<>();
    final Map<UUID, String> placesByWorld = new HashMap<>();
    final Set<String> unloadedPlaces = new HashSet<>();
    final List<UUID> refusedReturns = new ArrayList<>();
    CompletableFuture<TeleportOutcome> teleportResult = CompletableFuture.completedFuture(TeleportOutcome.MOVED);
    final Map<UUID, Integer> pings = new HashMap<>();
    final Map<UUID, Double> healths = new HashMap<>();
    int maxPlayers = 20;
    boolean scoreboardRemovalFails;
    boolean restoreFails;
    boolean saveFails;

    final List<UUID> saved = new ArrayList<>();
    final List<UUID> restored = new ArrayList<>();
    final List<String> returnedToStart = new ArrayList<>();
    final List<UUID> clearedInventories = new ArrayList<>();
    final List<StaffGameMode> gameModes = new ArrayList<>();
    final List<String> hideCalls = new ArrayList<>();
    final List<String> showCalls = new ArrayList<>();
    final List<String> openedInventories = new ArrayList<>();
    final Map<UUID, List<String>> messages = new HashMap<>();
    final Map<UUID, ScoreboardContent> scoreboards = new HashMap<>();
    final List<String> teleports = new ArrayList<>();

    @Override
    public boolean isOnline(UUID uuid) {
        return online.contains(uuid);
    }

    @Override
    public String playerName(UUID uuid) {
        return names.get(uuid);
    }

    @Override
    public Set<UUID> onlinePlayerUuids() {
        return new HashSet<>(online);
    }

    @Override
    public int onlinePlayerCount() {
        return online.size();
    }

    @Override
    public int maxPlayerCount() {
        return maxPlayers;
    }

    @Override
    public int playerPing(UUID uuid) {
        return pings.getOrDefault(uuid, 0);
    }

    @Override
    public double playerHealth(UUID uuid) {
        return healths.getOrDefault(uuid, 20.0);
    }

    @Override
    public void clearInventory(UUID uuid) {
        clearedInventories.add(uuid);
    }

    @Override
    public void setGameMode(UUID uuid, StaffGameMode mode) {
        gameModes.add(mode);
    }

    @Override
    public void setHotbarSlot(UUID uuid, int slot, String materialId, String displayName, List<String> lore) {
    }

    boolean holdsBelongings(UUID uuid) {
        return snapshots.containsKey(uuid);
    }

    @Override
    public String markKind() {
        return "fake";
    }

    @Override
    public Optional<UUID> custodyMark(UUID uuid) {
        return Optional.ofNullable(snapshots.get(uuid));
    }

    @Override
    public void clearMark(UUID uuid) {
        snapshots.remove(uuid);
    }

    @Override
    public boolean isAlive(UUID uuid) {
        return online.contains(uuid);
    }

    @Override
    public boolean hasSavedPlayerData(UUID uuid) {
        return true;
    }

    @Override
    public Belongings capture(UUID uuid) throws BelongingsException {
        if (saveFails) throw new BelongingsException("capture unsupported on this platform");
        return new FakeBelongings();
    }

    @Override
    public Belongings decode(String encoded) {
        return new FakeBelongings();
    }

    @Override
    public boolean isUnchanged(UUID uuid, Belongings captured) {
        return true;
    }

    @Override
    public void takeAway(UUID uuid, UUID custody) {
        snapshots.put(uuid, custody);
        saved.add(uuid);
    }

    @Override
    public void giveBack(UUID uuid, Belongings belongings) {
        if (restoreFails) {
            throw new UnsupportedOperationException("restore unsupported on this platform");
        }
        snapshots.remove(uuid);
        restored.add(uuid);
    }

    @Override
    public void leaveCreative(UUID uuid, String recordedGameMode) {
    }

    @Override
    public Optional<StartingLocation> currentLocation(UUID uuid) {
        return Optional.of(place(positions.getOrDefault(uuid, SPAWN)));
    }

    @Override
    public boolean canReturnTo(StartingLocation location) {
        return !unloadedPlaces.contains(placesByWorld.get(location.getWorldId()));
    }

    @Override
    public CompletableFuture<TeleportOutcome> teleportToStartingLocation(UUID uuid, StartingLocation location) {
        return teleportResult.thenApply(outcome -> {
            if (outcome == TeleportOutcome.MOVED) {
                String place = placesByWorld.get(location.getWorldId());
                positions.put(uuid, place);
                returnedToStart.add(place);
            }
            return outcome;
        });
    }

    @Override
    public void reportRefusedReturn(UUID uuid) {
        refusedReturns.add(uuid);
    }

    StartingLocation place(String name) {
        UUID worldId = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        placesByWorld.put(worldId, name);
        return new StartingLocation(worldId, 1.5, 64, -2.5, 90f, 10f);
    }

    @Override
    public void hidePlayer(UUID viewerUuid, UUID hiddenUuid) {
        hideCalls.add(viewerUuid + "<-" + hiddenUuid);
    }

    @Override
    public void showPlayer(UUID viewerUuid, UUID shownUuid) {
        showCalls.add(viewerUuid + "<-" + shownUuid);
    }

    @Override
    public void teleportToPlayer(UUID uuid, UUID targetUuid) {
        teleports.add(uuid + "->" + targetUuid);
    }

    @Override
    public void createScoreboard(UUID uuid, ScoreboardContent content) {
        scoreboards.put(uuid, content);
    }

    @Override
    public void updateScoreboard(UUID uuid, ScoreboardContent content) {
        scoreboards.put(uuid, content);
    }

    @Override
    public void removeScoreboard(UUID uuid) {
        if (scoreboardRemovalFails) {
            throw new UnsupportedOperationException("scoreboards unsupported on this platform");
        }
        scoreboards.remove(uuid);
    }

    @Override
    public void discardScoreboard(UUID uuid) {
        scoreboards.remove(uuid);
    }

    @Override
    public void clearScoreboards() {
        scoreboards.clear();
    }

    @Override
    public void openTargetInventory(UUID viewerUuid, UUID targetUuid) {
        openedInventories.add(viewerUuid + "->" + targetUuid);
    }

    @Override
    public void sendMessage(UUID uuid, String message) {
        messages.computeIfAbsent(uuid, k -> new ArrayList<>()).add(message);
    }

    void connect(UUID uuid, String name) {
        online.add(uuid);
        names.put(uuid, name);
    }

    private static final class FakeBelongings implements Belongings {
        @Override
        public String encoded() {
            return "belongings";
        }

        @Override
        public String gameMode() {
            return "SURVIVAL";
        }
    }
}
