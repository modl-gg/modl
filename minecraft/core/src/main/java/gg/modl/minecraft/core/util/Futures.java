package gg.modl.minecraft.core.util;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class Futures {
    private Futures() {
    }

    public static <T extends @Nullable Object> void whenComplete(CompletableFuture<T> future,
                                                                 BiConsumer<? super @Nullable T, ? super @Nullable Throwable> callback,
                                                                 PluginLogger logger) {
        future.<@Nullable Void>handle((result, failure) -> {
            callback.accept(result, failure);
            return null;
        }).exceptionally(callbackFailure -> {
            logger.warning("A modl callback failed", Throwables.rootCause(callbackFailure));
            return null;
        });
    }

    public static <T extends @Nullable Object> void whenSucceeded(CompletableFuture<T> future, Consumer<? super T> action,
                                                                  PluginLogger logger) {
        whenComplete(future, (result, failure) -> {
            if (failure != null) {
                logger.debug("A modl background request did not complete: " + Throwables.rootCause(failure));
                return;
            }
            action.accept(result);
        }, logger);
    }

    public static void ignoreResult(CompletableFuture<?> future, PluginLogger logger) {
        whenSucceeded(future, result -> {}, logger);
    }
}
