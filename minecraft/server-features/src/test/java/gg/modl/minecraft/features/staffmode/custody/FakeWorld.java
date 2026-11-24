package gg.modl.minecraft.features.staffmode.custody;

import gg.modl.minecraft.features.FeatureScheduler;
import gg.modl.minecraft.features.FeatureTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static gg.modl.minecraft.core.util.Java8Collections.failedFuture;

final class FakeWorld implements BelongingsPort, CustodyStore {
    static final String CREATIVE = "CREATIVE";
    static final String SURVIVAL = "SURVIVAL";
    static final String STAFF_ITEMS = "staff hotbar";

    static final class PlayerState {
        String items = "";
        String mode = SURVIVAL;
        UUID mark;
        boolean dead;

        PlayerState copy() {
            PlayerState copy = new PlayerState();
            copy.items = items;
            copy.mode = mode;
            copy.mark = mark;
            copy.dead = dead;
            return copy;
        }
    }

    private static final class FakeBelongings implements Belongings {
        private final String items;
        private final String mode;

        private FakeBelongings(String items, String mode) {
            this.items = items;
            this.mode = mode;
        }

        @Override
        public String encoded() {
            return mode + "|" + items;
        }

        @Override
        public String gameMode() {
            return mode;
        }
    }

    final Map<UUID, PlayerState> online = new HashMap<>();
    final Map<UUID, PlayerState> playerData = new HashMap<>();
    final Map<UUID, CustodyRecord> records = new HashMap<>();
    final List<CustodyRecord> setAside = new ArrayList<>();
    final ArrayDeque<Runnable> disk = new ArrayDeque<>();
    final Map<UUID, Boolean> loadedWithData = new HashMap<>();

    String markKind = "persistent-data";
    boolean captureFails;
    boolean decodeFails;
    boolean writesFail;
    boolean readsFail;
    boolean setAsideFails;
    boolean giveBackFails;
    boolean takeAwayFailsBeforeMark;
    boolean takeAwayFailsAfterMark;
    boolean markUnreadable;
    Runnable whileComparing = () -> { };

    void connect(UUID player) {
        PlayerState saved = playerData.get(player);
        loadedWithData.put(player, saved != null);
        online.put(player, saved == null ? new PlayerState() : saved.copy());
    }

    void savePlayer(UUID player) {
        playerData.put(player, online.get(player).copy());
    }

    void disconnect(UUID player) {
        savePlayer(player);
        online.remove(player);
    }

    void crash() {
        online.clear();
        disk.clear();
    }

    void runDisk() {
        while (!disk.isEmpty()) disk.poll().run();
    }

    PlayerState player(UUID player) {
        return online.get(player);
    }

    FeatureScheduler scheduler() {
        return new OnlinePlayerScheduler();
    }

    @Override
    public String markKind() {
        return markKind;
    }

    @Override
    public Optional<UUID> custodyMark(UUID player) {
        if (markUnreadable) throw new IllegalStateException("mark unreadable");
        return Optional.ofNullable(online.get(player).mark);
    }

    @Override
    public void clearMark(UUID player) {
        online.get(player).mark = null;
    }

    @Override
    public boolean isAlive(UUID player) {
        PlayerState state = online.get(player);
        return state != null && !state.dead;
    }

    @Override
    public boolean hasSavedPlayerData(UUID player) {
        return loadedWithData.getOrDefault(player, false);
    }

    @Override
    public Belongings capture(UUID player) throws BelongingsException {
        if (captureFails) throw new BelongingsException("item cannot be serialized");
        PlayerState state = online.get(player);
        return new FakeBelongings(state.items, state.mode);
    }

    @Override
    public Belongings decode(String encoded) throws BelongingsException {
        if (decodeFails) throw new BelongingsException("unreadable after an upgrade");
        int separator = encoded.indexOf('|');
        return new FakeBelongings(encoded.substring(separator + 1), encoded.substring(0, separator));
    }

    @Override
    public boolean isUnchanged(UUID player, Belongings captured) {
        PlayerState state = online.get(player);
        whileComparing.run();
        return captured.encoded().equals(state.mode + "|" + state.items);
    }

    @Override
    public void takeAway(UUID player, UUID custody) throws BelongingsException {
        if (takeAwayFailsBeforeMark) throw new BelongingsException("mark refused");
        PlayerState state = online.get(player);
        state.mark = custody;
        state.items = "";
        if (takeAwayFailsAfterMark) throw new BelongingsException("clear failed");
    }

    @Override
    public void giveBack(UUID player, Belongings belongings) throws BelongingsException {
        if (giveBackFails) throw new BelongingsException("game mode change refused");
        PlayerState state = online.get(player);
        state.items = ((FakeBelongings) belongings).items;
        state.mode = belongings.gameMode();
        state.mark = null;
    }

    @Override
    public void leaveCreative(UUID player, String recordedGameMode) {
        PlayerState state = online.get(player);
        if (CREATIVE.equals(state.mode)) state.mode = recordedGameMode != null ? recordedGameMode : SURVIVAL;
    }

    @Override
    public CompletableFuture<Optional<CustodyRecord>> load(UUID player) {
        CompletableFuture<Optional<CustodyRecord>> result = new CompletableFuture<>();
        disk.add(() -> {
            if (readsFail) result.completeExceptionally(new IllegalStateException("unreadable record"));
            else result.complete(Optional.ofNullable(records.get(player)));
        });
        return result;
    }

    @Override
    public CompletableFuture<Void> save(UUID player, CustodyRecord record) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        disk.add(() -> {
            if (writesFail) {
                result.completeExceptionally(new IllegalStateException("disk full"));
            } else {
                records.put(player, record);
                result.complete(null);
            }
        });
        return result;
    }

    @Override
    public CompletableFuture<Void> remove(UUID player) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        disk.add(() -> {
            records.remove(player);
            result.complete(null);
        });
        return result;
    }

    @Override
    public CompletableFuture<String> setAside(UUID player) {
        if (setAsideFails) return failedFuture(new IllegalStateException("cannot move"));
        CompletableFuture<String> result = new CompletableFuture<>();
        disk.add(() -> {
            CustodyRecord record = records.remove(player);
            setAside.add(record);
            result.complete("kept");
        });
        return result;
    }

    @Override
    public String describe(UUID player) {
        return player + ".custody";
    }

    @Override
    public void close() {
        runDisk();
    }

    private final class OnlinePlayerScheduler implements FeatureScheduler {
        @Override
        public void runOnMainThread(Runnable task) {
            task.run();
        }

        @Override
        public void runForPlayer(UUID player, Runnable task) {
            if (online.containsKey(player)) task.run();
        }

        @Override
        public void runLater(Runnable task, long delayTicks) {
            task.run();
        }

        @Override
        public void runForPlayerLater(UUID player, Runnable task, long delayTicks) {
            runForPlayer(player, task);
        }

        @Override
        public FeatureTask runTimerAsync(Runnable task, long delay, long period, TimeUnit unit) {
            return () -> { };
        }

        @Override
        public void cancelTask(FeatureTask task) {
        }
    }
}
