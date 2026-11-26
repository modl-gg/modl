package gg.modl.minecraft.spigot.features.folia;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class FoliaScheduler {

    private static final boolean IS_FOLIA;

    static {
        boolean folia;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException e) {
            folia = false;
        }
        IS_FOLIA = folia;
    }

    private final JavaPlugin plugin;

    private volatile @Nullable FoliaApi api;

    public FoliaScheduler(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public static boolean isFolia() {
        return IS_FOLIA;
    }

    private FoliaApi api() {
        FoliaApi resolved = api;
        if (resolved != null) return resolved;
        synchronized (this) {
            resolved = api;
            if (resolved == null) {
                resolved = FoliaApi.resolve();
                api = resolved;
            }
            return resolved;
        }
    }

    public void runGlobal(Runnable task) {
        FoliaApi folia = api();
        try {
            Object globalScheduler = folia.getGlobalRegionScheduler.invoke(null);
            Consumer<Object> consumer = scheduledTask -> task.run();
            folia.globalRun.invoke(globalScheduler, plugin, consumer);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("[server-features] Failed to run global Folia task", e);
        }
    }

    public void runForEntity(Entity entity, Runnable task) {
        runForEntity(entity, task, null);
    }

    public boolean runForEntity(Entity entity, Runnable task, @Nullable Runnable retired) {
        FoliaApi folia = api();
        try {
            Object entityScheduler = folia.entityGetScheduler.invoke(entity);
            Consumer<Object> consumer = scheduledTask -> task.run();
            return folia.entityRun.invoke(entityScheduler, plugin, consumer, retired) != null;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("[server-features] Failed to run entity Folia task", e);
        }
    }

    public void runForEntityLater(Entity entity, Runnable task, long delayTicks) {
        FoliaApi folia = api();
        try {
            Object entityScheduler = folia.entityGetScheduler.invoke(entity);
            folia.entityExecute.invoke(entityScheduler, plugin, task, (Runnable) null, delayTicks);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("[server-features] Failed to run delayed entity Folia task", e);
        }
    }

    public @Nullable Object runAsyncTimer(Runnable task, long delay, long period, TimeUnit unit) {
        FoliaApi folia = api();
        try {
            Object asyncScheduler = folia.getAsyncScheduler.invoke(null);
            Consumer<Object> consumer = scheduledTask -> task.run();
            return folia.asyncRunAtFixedRate.invoke(asyncScheduler, plugin, consumer, delay, period, unit);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("[server-features] Failed to run async Folia timer", e);
        }
    }

    public void runAsync(Runnable task) {
        FoliaApi folia = api();
        try {
            Object asyncScheduler = folia.getAsyncScheduler.invoke(null);
            Consumer<Object> consumer = scheduledTask -> task.run();
            folia.asyncRunNow.invoke(asyncScheduler, plugin, consumer);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("[server-features] Failed to run async Folia task", e);
        }
    }

    public void cancelFoliaTask(@Nullable Object scheduledTask) {
        if (scheduledTask == null) return;
        try {
            api().taskCancel.invoke(scheduledTask);
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().log(Level.WARNING, "[server-features] Failed to cancel Folia task", e);
        }
    }

    private static final class FoliaApi {
        private final Method getGlobalRegionScheduler;
        private final Method globalRun;
        private final Method getAsyncScheduler;
        private final Method asyncRunAtFixedRate;
        private final Method asyncRunNow;
        private final Method entityGetScheduler;
        private final Method entityRun;
        private final Method entityExecute;
        private final Method taskCancel;

        private FoliaApi(Method getGlobalRegionScheduler, Method globalRun, Method getAsyncScheduler, Method asyncRunAtFixedRate,
                         Method asyncRunNow, Method entityGetScheduler, Method entityRun, Method entityExecute, Method taskCancel) {
            this.getGlobalRegionScheduler = getGlobalRegionScheduler;
            this.globalRun = globalRun;
            this.getAsyncScheduler = getAsyncScheduler;
            this.asyncRunAtFixedRate = asyncRunAtFixedRate;
            this.asyncRunNow = asyncRunNow;
            this.entityGetScheduler = entityGetScheduler;
            this.entityRun = entityRun;
            this.entityExecute = entityExecute;
            this.taskCancel = taskCancel;
        }

        private static FoliaApi resolve() {
            try {
                Method getGlobalRegionScheduler = Bukkit.class.getMethod("getGlobalRegionScheduler");
                Class<?> globalScheduler = getGlobalRegionScheduler.getReturnType();
                Method getAsyncScheduler = Bukkit.class.getMethod("getAsyncScheduler");
                Class<?> asyncScheduler = getAsyncScheduler.getReturnType();
                Method asyncRunAtFixedRate = asyncScheduler.getMethod("runAtFixedRate",
                        Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class);
                Class<?> scheduledTask = asyncRunAtFixedRate.getReturnType();
                Method entityGetScheduler = Entity.class.getMethod("getScheduler");
                Class<?> entityScheduler = entityGetScheduler.getReturnType();
                return new FoliaApi(
                        getGlobalRegionScheduler,
                        globalScheduler.getMethod("run", Plugin.class, Consumer.class),
                        getAsyncScheduler,
                        asyncRunAtFixedRate,
                        asyncScheduler.getMethod("runNow", Plugin.class, Consumer.class),
                        entityGetScheduler,
                        entityScheduler.getMethod("run", Plugin.class, Consumer.class, Runnable.class),
                        entityScheduler.getMethod("execute", Plugin.class, Runnable.class, Runnable.class, long.class),
                        scheduledTask.getMethod("cancel"));
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("[server-features] Folia scheduler API unavailable", e);
            }
        }
    }
}
