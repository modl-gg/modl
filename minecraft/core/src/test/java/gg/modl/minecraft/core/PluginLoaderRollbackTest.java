package gg.modl.minecraft.core;

import gg.modl.minecraft.core.imports.sql.LoadedDriverLibraries;
import gg.modl.minecraft.api.PunishmentTypeClassifier;
import gg.modl.minecraft.api.PunishmentTypeClassifiers;
import gg.modl.minecraft.core.boot.CoreState;
import gg.modl.minecraft.core.service.ChatMessageCache;
import gg.modl.minecraft.core.support.FakePlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import revxrsal.commands.Lamp;
import revxrsal.commands.command.CommandActor;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginLoaderRollbackTest {
    private static final String LOGIN_CACHE_THREAD = "modl-login-cache-cleanup";

    @TempDir
    Path dataDirectory;

    @Test
    void aConstructorFailureUnwindsEverythingTheConstructorStarted() throws InterruptedException {
        long loginCacheThreadsBefore = liveThreads(LOGIN_CACHE_THREAD);
        FailingRegistrationPlatform platform = new FailingRegistrationPlatform();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> PluginLoader.create(
                platform, dataDirectory, new ChatMessageCache(), () -> CoreState.STARTING, new LoadedDriverLibraries(), false, false));

        assertEquals(FailingRegistrationPlatform.FAILURE, thrown.getMessage());
        assertTrue(platform.commandsBeforeFailure > 0);
        assertTrue(platform.lamp().registry().commands().isEmpty());

        assertNotNull(platform.servicesBeforeFailure);
        assertNotSame(platform.servicesBeforeFailure, PluginServices.installed().orElse(null));
        assertNotSame(platform.classifierBeforeFailure, PunishmentTypeClassifiers.active());

        assertTrue(awaitAtMost(loginCacheThreadsBefore, LOGIN_CACHE_THREAD));
    }

    private static boolean awaitAtMost(long expected, String threadName) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (liveThreads(threadName) <= expected) return true;
            TimeUnit.MILLISECONDS.sleep(20);
        }
        return liveThreads(threadName) <= expected;
    }

    private static long liveThreads(String name) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive() && name.equals(thread.getName()))
                .count();
    }

    private static final class FailingRegistrationPlatform extends FakePlatform {
        private static final String FAILURE = "the command map is unavailable";

        private int commandsBeforeFailure;
        private PluginServices servicesBeforeFailure;
        private PunishmentTypeClassifier classifierBeforeFailure;

        @Override
        public void finalizeLampRegistration(Lamp<? extends CommandActor> registered) {
            commandsBeforeFailure = registered.registry().commands().size();
            servicesBeforeFailure = PluginServices.get();
            classifierBeforeFailure = PunishmentTypeClassifiers.active();
            throw new IllegalStateException(FAILURE);
        }
    }
}
