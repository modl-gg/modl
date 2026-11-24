package gg.modl.minecraft.spigot.features.folia;

import gg.modl.minecraft.core.util.Futures;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.features.TeleportOutcome;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

public final class AsyncTeleporter {
    private static final @Nullable Method TELEPORT_ASYNC = resolveTeleportAsync();

    private final PluginLogger logger;

    public AsyncTeleporter(PluginLogger logger) {
        this.logger = logger;
    }

    public void teleport(Entity entity, Location destination) {
        Futures.whenSucceeded(attempt(entity, destination), outcome -> {
            if (outcome == TeleportOutcome.REFUSED) reportRefused(entity);
        }, logger);
    }

    public void reportRefused(Entity entity) {
        logger.warning("[server-features] Teleport of " + entity.getName() + " was refused");
    }

    public CompletableFuture<TeleportOutcome> attempt(Entity entity, Location destination) {
        Method teleportAsync = TELEPORT_ASYNC;
        if (teleportAsync == null) {
            return CompletableFuture.completedFuture(entity.teleport(destination) ? TeleportOutcome.MOVED : TeleportOutcome.REFUSED);
        }
        try {
            CompletableFuture<?> pending = (CompletableFuture<?>) teleportAsync.invoke(entity, destination);
            return pending.handle((moved, failure) -> {
                if (failure == null) return Boolean.TRUE.equals(moved) ? TeleportOutcome.MOVED : TeleportOutcome.REFUSED;
                logger.warning("[server-features] Failed to teleport " + entity.getName(), failure);
                return TeleportOutcome.FAILED;
            });
        } catch (ReflectiveOperationException e) {
            logger.warning("[server-features] Failed to teleport " + entity.getName(), e);
            return CompletableFuture.completedFuture(TeleportOutcome.FAILED);
        }
    }

    private static @Nullable Method resolveTeleportAsync() {
        try {
            return Entity.class.getMethod("teleportAsync", Location.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
