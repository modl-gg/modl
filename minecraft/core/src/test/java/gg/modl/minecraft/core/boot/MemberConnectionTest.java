package gg.modl.minecraft.core.boot;

import gg.modl.minecraft.core.connection.RemoteSettings;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MemberConnectionTest {
    private static final String CONFIG_PATH = "plugins/modl/connection.yml";
    private static final RemoteSettings NO_KEY = RemoteSettings.builder().url("https://modl.example.com").build();
    private static final RemoteSettings WITH_KEY = RemoteSettings.builder().url("https://modl.example.com").apiKey("msk_abc").build();

    private final RecordingPluginLogger logger = new RecordingPluginLogger();
    private final List<RemoteSettings> created = new ArrayList<>();
    private final List<MemberLifecycle> lifecycles = new ArrayList<>();
    private final List<Runnable> timers = new ArrayList<>();

    @Test
    void withoutAKeyTheServerStaysIdleAndSaysWhereToGetOne() {
        MemberConnection connection = connection(NO_KEY);

        connection.start();

        assertTrue(connection.waitsForKey());
        assertTrue(created.isEmpty());
        assertEquals(MemberConnection.missingKeyNotice(CONFIG_PATH), logger.warnings().get(0));
        assertTrue(logger.warnings().get(0).contains("Panel > Settings > General > Minecraft servers > Add server"));
        assertTrue(logger.warnings().get(0).contains("/modlserver reload"));
        assertEquals(1, timers.size());
        assertThrows(IllegalStateException.class, connection::httpClient);
    }

    @Test
    void aReloadWithTheNewKeyConnectsOnceAndStopsTheReminder() {
        MemberConnection connection = connection(NO_KEY);
        connection.start();

        connection.reload(WITH_KEY);
        connection.reload(WITH_KEY);

        assertEquals(1, created.size());
        assertFalse(connection.waitsForKey());
        verify(lifecycles.get(0)).start();
        assertTrue(timers.isEmpty());
        assertEquals(MemberConnection.connectingNotice("https://modl.example.com"), logger.infos().get(0));
    }

    @Test
    void aChangedKeyReplacesTheRunningConnection() {
        MemberConnection connection = connection(WITH_KEY);
        connection.start();

        connection.reload(RemoteSettings.builder().url("https://modl.example.com").apiKey("msk_new").build());

        assertEquals(2, created.size());
        verify(lifecycles.get(0)).shutdown();
        verify(lifecycles.get(1)).start();
    }

    @Test
    void aKeyThatIsPresentAtStartConnectsWithoutTheNotice() {
        MemberConnection connection = connection(WITH_KEY);

        connection.start();

        assertTrue(logger.warnings().isEmpty());
        verify(lifecycles.get(0)).start();
        verify(lifecycles.get(0), never()).shutdown();
    }

    private MemberConnection connection(RemoteSettings settings) {
        return new MemberConnection(settings, CONFIG_PATH, logger, remote -> {
            created.add(remote);
            MemberLifecycle lifecycle = mock(MemberLifecycle.class);
            lifecycles.add(lifecycle);
            return lifecycle;
        }, (periodMillis, task) -> {
            timers.add(task);
            return () -> timers.remove(task);
        });
    }
}
