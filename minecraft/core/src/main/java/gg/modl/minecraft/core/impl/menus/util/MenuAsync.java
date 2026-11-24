package gg.modl.minecraft.core.impl.menus.util;

import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import gg.modl.minecraft.api.http.ApiClientException;
import gg.modl.minecraft.api.http.PanelUnavailableException;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.core.util.ApiFailures;
import gg.modl.minecraft.core.util.Throwables;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class MenuAsync {
    private static final String ACTION_FAILED_KEY = "menus.common.action_failed";
    private static final String SAVED_BUT_INCOMPLETE_KEY = "menus.common.saved_but_incomplete";

    private MenuAsync() {}

    public static void displayWhenLoaded(Platform platform, CompletableFuture<Void> dataFuture,
                                         CirrusPlayerWrapper player, Consumer<CirrusPlayerWrapper> displayAction) {
        if (player == null) return;
        consume(platform, player.uuid(), dataFuture,
                loaded -> platform.runOnMainThread(() -> displayAction.accept(player)));
    }

    public static <T extends @Nullable Object> void consume(Platform platform, UUID viewer, CompletableFuture<T> work,
                                                            Consumer<? super T> onResult) {
        handle(platform, viewer, ACTION_FAILED_KEY, work, onResult);
    }

    public static void afterSave(Platform platform, UUID viewer, CompletableFuture<?> work) {
        handle(platform, viewer, SAVED_BUT_INCOMPLETE_KEY, work, result -> {});
    }

    public static <T extends @Nullable Object> void afterSave(Platform platform, UUID viewer, CompletableFuture<T> work,
                                                              Consumer<? super T> onResult) {
        handle(platform, viewer, SAVED_BUT_INCOMPLETE_KEY, work, onResult);
    }

    private static <T extends @Nullable Object> void handle(Platform platform, UUID viewer, String failureKey,
                                                            CompletableFuture<T> work, Consumer<? super T> onResult) {
        work.thenAccept(onResult).exceptionally(failure -> {
            reportFailure(platform, viewer, failureKey, failure);
            return null;
        });
    }

    private static void reportFailure(Platform platform, UUID viewer, String failureKey, Throwable failure) {
        Throwable cause = Throwables.rootCause(failure);
        if (cause instanceof PanelUnavailableException || cause instanceof ApiClientException) {
            platform.getLogger().debug("A menu action for " + viewer + " was refused: " + cause);
        } else {
            platform.getLogger().warning("A menu action for " + viewer + " failed", cause);
        }
        String message = ApiFailures.describe(PluginServices.locale(), failureKey, failure);
        platform.runOnMainThread(() -> platform.sendMessage(viewer, message));
    }
}
