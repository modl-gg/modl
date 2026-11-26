package gg.modl.minecraft.features.statwipe;

import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.request.StatWipeAcknowledgeRequest;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.features.FeaturePlayer;
import gg.modl.minecraft.features.FeaturePlayerProvider;
import gg.modl.minecraft.features.FeatureScheduler;
import gg.modl.minecraft.features.FeatureTask;
import gg.modl.minecraft.features.config.ServerFeaturesConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemberStatWipeHandlerTest {
    private static final UUID PLAYER = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

    private final List<String> commands = new ArrayList<>();
    private final List<StatWipeAcknowledgeRequest> acknowledged = new ArrayList<>();
    private final List<Runnable> mainThread = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private CompletableFuture<Void> acknowledgement = CompletableFuture.completedFuture(null);
    private final MemberStatWipeHandler handler = new MemberStatWipeHandler(
            new StatWipeHandler(logger(), config("eco reset {player} {uuid}"), players()), scheduler(), this::httpClient,
            () -> "lobby", logger());

    @Test
    void aWipeRunsOnTheMainThreadAndIsAcknowledgedOnce() {
        assertTrue(handler.execute("Steve", PLAYER, "punish01"));
        assertTrue(commands.isEmpty());

        runMainThread();

        assertEquals(Collections.singletonList("eco reset Steve " + PLAYER), commands);
        assertEquals(1, acknowledged.size());
        assertEquals("lobby", acknowledged.get(0).getServerName());
        assertFalse(handler.execute("Steve", PLAYER, "punish01"));
    }

    @Test
    void aWipeStillInFlightIsNotStartedTwice() {
        assertTrue(handler.execute("Steve", PLAYER, "punish01"));
        assertFalse(handler.execute("Steve", PLAYER, "punish01"));
        assertEquals(1, mainThread.size());
    }

    @Test
    void aFailedAcknowledgementLetsTheNextPushRetry() {
        acknowledgement = new CompletableFuture<>();
        acknowledgement.completeExceptionally(new IllegalStateException("offline"));
        handler.execute("Steve", PLAYER, "punish01");
        runMainThread();

        acknowledgement = CompletableFuture.completedFuture(null);
        assertTrue(handler.execute("Steve", PLAYER, "punish01"));
    }

    @Test
    void namesThatCouldInjectConsoleCommandsAreSkipped() {
        for (String name : Arrays.asList("Steve; op Steve", "a b", "", "abcdefghijklmnopq", "Steve\nop")) {
            assertFalse(handler.execute(name, PLAYER, "punish01"));
        }
        assertFalse(handler.execute("Steve", PLAYER, "bad id;"));
        assertTrue(mainThread.isEmpty());
        assertEquals(6, warnings.size());
    }

    @Test
    void bedrockPlayersWithAFloodgatePrefixAreWipedAndAcknowledged() {
        assertTrue(handler.execute(".BedrockSteve", PLAYER, "punish01"));
        assertTrue(handler.execute("*Bedrock", PLAYER, "punish02"));
        assertTrue(handler.execute("+Bedrock", PLAYER, "punish03"));

        runMainThread();

        assertEquals(Arrays.asList("eco reset .BedrockSteve " + PLAYER, "eco reset *Bedrock " + PLAYER,
                "eco reset +Bedrock " + PLAYER), commands);
        assertEquals(3, acknowledged.size());
    }

    @Test
    void theConsoleSinkRefusesUnsafeNamesWhicheverPathCallsIt() {
        StatWipeHandler direct = new StatWipeHandler(logger(), config("eco reset {player}"), players());

        assertFalse(direct.execute("Steve; op Steve", PLAYER.toString(), "punish01"));
        assertTrue(direct.execute(".Bedrock", PLAYER.toString(), "punish01"));
        assertEquals(Collections.singletonList("eco reset .Bedrock"), commands);
    }

    private void runMainThread() {
        List<Runnable> tasks = new ArrayList<>(mainThread);
        mainThread.clear();
        tasks.forEach(Runnable::run);
    }

    private ModlHttpClient httpClient() {
        return (ModlHttpClient) Proxy.newProxyInstance(ModlHttpClient.class.getClassLoader(), new Class<?>[] {ModlHttpClient.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("acknowledgeStatWipe")) throw new UnsupportedOperationException(method.getName());
                    acknowledged.add((StatWipeAcknowledgeRequest) args[0]);
                    return acknowledgement;
                });
    }

    private FeatureScheduler scheduler() {
        return new FeatureScheduler() {
            @Override public void runOnMainThread(Runnable task) { mainThread.add(task); }
            @Override public void runForPlayer(UUID playerUuid, Runnable task) { mainThread.add(task); }
            @Override public void runForPlayer(UUID playerUuid, Runnable task, Runnable gone) { mainThread.add(task); }
            @Override public void runLater(Runnable task, long delayTicks) { mainThread.add(task); }
            @Override public void runAsync(Runnable task) { task.run(); }
            @Override public void runForPlayerLater(UUID playerUuid, Runnable task, long delayTicks) { mainThread.add(task); }
            @Override public FeatureTask runTimerAsync(Runnable task, long delay, long period, TimeUnit unit) { return null; }
            @Override public void cancelTask(FeatureTask task) { }
        };
    }

    private FeaturePlayerProvider players() {
        return new FeaturePlayerProvider() {
            @Override public Collection<FeaturePlayer> getOnlinePlayers() { return Collections.emptyList(); }
            @Override public boolean isOnline(UUID uuid) { return false; }
            @Override public void dispatchConsoleCommand(String command) { commands.add(command); }
        };
    }

    private PluginLogger logger() {
        return new PluginLogger() {
            @Override public void info(String message) { }
            @Override public void warning(String message) { warnings.add(message); }
            @Override public void severe(String message) { }
        };
    }

    private static ServerFeaturesConfig config(String command) {
        return ServerFeaturesConfig.builder().statWipeCommands(Collections.singletonList(command)).build();
    }
}
