package gg.modl.minecraft.core.impl.menus.util;

import gg.modl.minecraft.api.http.PanelUnavailableException;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.support.FakePlatform;
import gg.modl.minecraft.core.support.TestPluginServices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuAsyncTest {
    private static final UUID VIEWER = UUID.fromString("123e4567-e89b-12d3-a456-426614174900");

    private final FakePlatform platform = new FakePlatform();
    private final LocaleManager locale = new LocaleManager();
    private final List<String> results = new ArrayList<>();

    @BeforeEach
    void installServices() {
        TestPluginServices.install(new Cache(new CachedProfileRegistry()), locale);
    }

    @Test
    void aResultIsHandedOnWithoutAMessageOrAWarning() {
        MenuAsync.consume(platform, VIEWER, CompletableFuture.completedFuture("loaded"), results::add);

        assertEquals(Collections.singletonList("loaded"), results);
        assertTrue(platform.sentMessages().isEmpty());
        assertTrue(platform.recordingLogger().warnings().isEmpty());
    }

    @Test
    void aFailedRequestIsLoggedOnceAndTheViewerIsTold() {
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("backend refused"));

        MenuAsync.consume(platform, VIEWER, failed, results::add);

        assertTrue(results.isEmpty());
        assertEquals(1, platform.recordingLogger().warnings().size());
        assertTrue(platform.recordingLogger().warnings().get(0).contains("backend refused"));
        assertEquals(Collections.singletonList(locale.getMessage("menus.common.action_failed")), messagesToViewer());
    }

    @Test
    void aFailureWhileHandlingTheResultIsReportedTheSameWay() {
        MenuAsync.consume(platform, VIEWER, CompletableFuture.completedFuture("loaded"), loaded -> {
            throw new IllegalStateException("render failed");
        });

        assertEquals(1, platform.recordingLogger().warnings().size());
        assertEquals(Collections.singletonList(locale.getMessage("menus.common.action_failed")), messagesToViewer());
    }

    @Test
    void aRestartingPanelIsNamedInsteadOfTheGenericMessage() {
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(new PanelUnavailableException("/v1/minecraft/players", 503, "restarting"));

        MenuAsync.consume(platform, VIEWER, failed, results::add);

        assertEquals(Collections.singletonList(locale.getMessage("api_errors.panel_unavailable")), messagesToViewer());
    }

    @Test
    void aFailureAfterASaveSaysTheChangeWasSavedInsteadOfAskingForARetry() {
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("refresh failed"));

        MenuAsync.afterSave(platform, VIEWER, failed);

        assertEquals(1, platform.recordingLogger().warnings().size());
        assertEquals(Collections.singletonList(locale.getMessage("menus.common.saved_but_incomplete")), messagesToViewer());
    }

    private List<String> messagesToViewer() {
        List<String> messages = new ArrayList<>();
        for (FakePlatform.Message message : platform.sentMessages()) {
            if (VIEWER.equals(message.uuid())) messages.add(message.message());
        }
        return messages;
    }
}
