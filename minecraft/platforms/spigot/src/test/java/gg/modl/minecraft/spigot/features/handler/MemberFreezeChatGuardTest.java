package gg.modl.minecraft.spigot.features.handler;

import gg.modl.minecraft.features.FeatureScheduler;
import gg.modl.minecraft.features.FeatureTask;
import gg.modl.minecraft.features.locale.FeaturesLocaleManager;
import gg.modl.minecraft.features.freeze.FrozenPlayers;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemberFreezeChatGuardTest {

    @Test
    void frozenAsyncChatBroadcastRunsThroughScheduler() {
        FeaturesLocaleManager localeManager = mock(FeaturesLocaleManager.class);
        RecordingScheduler scheduler = new RecordingScheduler();
        FakeFrozenPlayers frozenPlayers = new FakeFrozenPlayers();
        StaffModeHandler staffModeHandler = mock(StaffModeHandler.class);
        MemberFreezeChatGuard handler = new MemberFreezeChatGuard(
                mock(JavaPlugin.class), localeManager, scheduler, frozenPlayers, staffModeHandler);

        Player frozenPlayer = mock(Player.class);
        Player staffPlayer = mock(Player.class);
        UUID frozenUuid = UUID.randomUUID();
        UUID staffUuid = UUID.randomUUID();
        AtomicBoolean scheduledTaskRunning = new AtomicBoolean(false);

        when(frozenPlayer.getUniqueId()).thenReturn(frozenUuid);
        when(frozenPlayer.getName()).thenReturn("Frozen");
        when(staffPlayer.getUniqueId()).thenReturn(staffUuid);
        when(staffModeHandler.isInStaffMode(staffUuid)).thenReturn(true);
        when(localeManager.getMessage(eq("freeze.chat"), anyMap())).thenReturn("Frozen: hello");

        frozenPlayers.frozen.add(frozenUuid);

        AsyncPlayerChatEvent event = mock(AsyncPlayerChatEvent.class);
        when(event.getPlayer()).thenReturn(frozenPlayer);
        when(event.getMessage()).thenReturn("hello");

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(invocation -> {
                assertTrue(scheduledTaskRunning.get(), "online players must be collected from the scheduled task");
                return Collections.singletonList(staffPlayer);
            });
            bukkit.when(() -> Bukkit.getPlayer(frozenUuid)).thenReturn(frozenPlayer);
            bukkit.when(() -> Bukkit.getPlayer(staffUuid)).thenReturn(staffPlayer);

            handler.onChat(event);

            verify(event).setCancelled(true);
            verify(frozenPlayer, never()).sendMessage("Frozen: hello");
            verify(staffPlayer, never()).sendMessage("Frozen: hello");
            assertEquals(1, scheduler.syncRuns);

            scheduledTaskRunning.set(true);
            scheduler.syncTask.run();
        }

        verify(frozenPlayer).sendMessage("Frozen: hello");
        verify(staffPlayer).sendMessage("Frozen: hello");
    }

    @Test
    void frozenPlayerCannotRunCommands() {
        FeaturesLocaleManager localeManager = mock(FeaturesLocaleManager.class);
        FakeFrozenPlayers frozenPlayers = new FakeFrozenPlayers();
        MemberFreezeChatGuard handler = new MemberFreezeChatGuard(
                mock(JavaPlugin.class), localeManager, new RecordingScheduler(), frozenPlayers,
                mock(StaffModeHandler.class));

        Player frozenPlayer = mock(Player.class);
        UUID frozenUuid = UUID.randomUUID();
        when(frozenPlayer.getUniqueId()).thenReturn(frozenUuid);
        when(localeManager.getMessage("freeze.no_commands")).thenReturn("No commands while frozen");
        frozenPlayers.frozen.add(frozenUuid);

        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getPlayer()).thenReturn(frozenPlayer);

        handler.onCommand(event);

        verify(event).setCancelled(true);
        verify(frozenPlayer).sendMessage("No commands while frozen");
    }

    private static class RecordingScheduler implements FeatureScheduler {
        private Runnable syncTask;
        private int syncRuns;

        @Override
        public void runOnMainThread(Runnable task) {
            syncRuns++;
            syncTask = task;
        }

        @Override
        public void runForPlayer(UUID playerUuid, Runnable task) {
            if (syncTask != null) {
                task.run();
            }
        }

        @Override
        public void runLater(Runnable task, long delayTicks) {
        }

        @Override
        public void runForPlayerLater(UUID playerUuid, Runnable task, long delayTicks) {
        }

        @Override
        public FeatureTask runTimerAsync(Runnable task, long delay, long period, TimeUnit unit) {
            return () -> {};
        }

        @Override
        public void cancelTask(FeatureTask task) {
        }
    }

    private static final class FakeFrozenPlayers implements FrozenPlayers {
        private final Set<UUID> frozen = new HashSet<>();

        @Override
        public boolean isFrozen(UUID uuid) {
            return frozen.contains(uuid);
        }

        @Override
        public Set<UUID> frozenPlayers() {
            return new HashSet<>(frozen);
        }
    }
}
