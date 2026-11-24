package gg.modl.minecraft.spigot.features;

import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.core.util.Scheduling;
import gg.modl.minecraft.features.FeatureScheduler;
import gg.modl.minecraft.features.FeatureTask;
import gg.modl.minecraft.spigot.features.folia.FoliaScheduler;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.bukkit.scheduler.BukkitTask;

public class SpigotFeatureScheduler implements FeatureScheduler {
    private final JavaPlugin plugin;
    private final PluginLogger logger;
    @Getter private final boolean folia;
    private final FoliaScheduler foliaScheduler;
    private final @Nullable ScheduledExecutorService delayExecutor;

    public SpigotFeatureScheduler(JavaPlugin plugin) {
        this(plugin, FoliaScheduler.isFolia(), null);
    }

    SpigotFeatureScheduler(JavaPlugin plugin, boolean folia, @Nullable ScheduledExecutorService delayExecutor) {
        this.plugin = plugin;
        this.logger = PluginLogger.fromJul(plugin.getLogger());
        this.folia = folia;
        this.foliaScheduler = new FoliaScheduler(plugin);
        if (!folia) {
            this.delayExecutor = null;
        } else {
            this.delayExecutor = delayExecutor != null ? delayExecutor : createDelayExecutor();
        }
    }

    private static ScheduledExecutorService createDelayExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "modl-features-delay");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void runOnMainThread(Runnable task) {
        if (folia) {
            foliaScheduler.runGlobal(task);
        } else {
            if (Bukkit.isPrimaryThread()) {
                task.run();
            } else {
                Bukkit.getScheduler().runTask(plugin, task);
            }
        }
    }

    @Override
    public void runForPlayer(UUID playerUuid, Runnable task) {
        if (folia) {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player != null && player.isOnline()) {
                foliaScheduler.runForEntity(player, task);
            }
        } else {
            runOnMainThread(task);
        }
    }

    @Override
    public void runLater(Runnable task, long delayTicks) {
        ScheduledExecutorService foliaDelays = delayExecutor;
        if (foliaDelays != null) {
            long delayMs = delayTicks * 50L;
            Scheduling.after(foliaDelays, () -> foliaScheduler.runGlobal(task), delayMs, TimeUnit.MILLISECONDS, logger);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }

    @Override
    public void runForPlayerLater(UUID playerUuid, Runnable task, long delayTicks) {
        if (folia) {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player != null && player.isOnline()) {
                foliaScheduler.runForEntityLater(player, task, delayTicks);
            }
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }

    @Override
    public FeatureTask runTimerAsync(Runnable task, long delay, long period, TimeUnit unit) {
        if (folia) {
            Object foliaTask = foliaScheduler.runAsyncTimer(task, delay, period, unit);
            return () -> foliaScheduler.cancelFoliaTask(foliaTask);
        } else {
            long delayTicks = unit.toSeconds(delay) * 20L;
            long periodTicks = unit.toSeconds(period) * 20L;
            if (delayTicks < 1) delayTicks = 1;
            if (periodTicks < 1) periodTicks = 1;
            BukkitTask bukkitTask = Bukkit.getScheduler()
                    .runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks);
            return bukkitTask::cancel;
        }
    }

    @Override
    public void cancelTask(FeatureTask task) {
        task.cancel();
    }

    public void shutdown() {
        ScheduledExecutorService foliaDelays = delayExecutor;
        if (foliaDelays != null) foliaDelays.shutdownNow();
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }
}
