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
    private volatile @Nullable Method entityRunMethod;
    private volatile @Nullable Method entityExecuteDelayedMethod;
    private volatile @Nullable Method scheduledTaskCancelMethod;

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
        FoliaApi folia = api();
        try {
            Object entityScheduler = folia.entityGetScheduler.invoke(entity);
            Method run = entityRunMethod;
            if (run == null) {
                run = entityScheduler.getClass().getMethod("run", Plugin.class, Consumer.class, Runnable.class);
                entityRunMethod = run;
            }
            Consumer<Object> consumer = scheduledTask -> task.run();
            run.invoke(entityScheduler, plugin, consumer, (Runnable) null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("[server-features] Failed to run entity Folia task", e);
        }
    }

    public void runForEntityLater(Entity entity, Runnable task, long delayTicks) {
        FoliaApi folia = api();
        try {
            Object entityScheduler = folia.entityGetScheduler.invoke(entity);
            Method executeDelayed = entityExecuteDelayedMethod;
            if (executeDelayed == null) {
                executeDelayed = entityScheduler.getClass().getMethod("execute",
                        Plugin.class, Runnable.class, Runnable.class, long.class);
                entityExecuteDelayedMethod = executeDelayed;
            }
            executeDelayed.invoke(entityScheduler, plugin, task, (Runnable) null, delayTicks);
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

    public void cancelFoliaTask(@Nullable Object scheduledTask) {
        if (scheduledTask == null) return;
        try {
            Method cancel = scheduledTaskCancelMethod;
            if (cancel == null) {
                cancel = scheduledTask.getClass().getMethod("cancel");
                scheduledTaskCancelMethod = cancel;
            }
            cancel.invoke(scheduledTask);
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().log(Level.WARNING, "[server-features] Failed to cancel Folia task", e);
        }
    }

    private static final class FoliaApi {
        private final Method getGlobalRegionScheduler;
        private final Method globalRun;
        private final Method getAsyncScheduler;
        private final Method asyncRunAtFixedRate;
        private final Method entityGetScheduler;

        private FoliaApi(Method getGlobalRegionScheduler, Method globalRun, Method getAsyncScheduler,
                         Method asyncRunAtFixedRate, Method entityGetScheduler) {
            this.getGlobalRegionScheduler = getGlobalRegionScheduler;
            this.globalRun = globalRun;
            this.getAsyncScheduler = getAsyncScheduler;
            this.asyncRunAtFixedRate = asyncRunAtFixedRate;
            this.entityGetScheduler = entityGetScheduler;
        }

        private static FoliaApi resolve() {
            try {
                Method getGlobalRegionScheduler = Bukkit.class.getMethod("getGlobalRegionScheduler");
                Method globalRun = schedulerType(getGlobalRegionScheduler)
                        .getMethod("run", Plugin.class, Consumer.class);
                Method getAsyncScheduler = Bukkit.class.getMethod("getAsyncScheduler");
                Method asyncRunAtFixedRate = schedulerType(getAsyncScheduler).getMethod("runAtFixedRate",
                        Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class);
                return new FoliaApi(getGlobalRegionScheduler, globalRun, getAsyncScheduler, asyncRunAtFixedRate,
                        Entity.class.getMethod("getScheduler"));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("[server-features] Folia scheduler API unavailable", e);
            }
        }

        private static Class<?> schedulerType(Method accessor) throws ReflectiveOperationException {
            Object scheduler = accessor.invoke(null);
            if (scheduler == null) throw new NoSuchMethodException(accessor.getName() + " returned no scheduler");
            return scheduler.getClass();
        }
    }
}
