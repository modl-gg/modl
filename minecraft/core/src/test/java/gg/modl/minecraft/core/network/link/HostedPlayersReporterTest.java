package gg.modl.minecraft.core.network.link;

import gg.modl.minecraft.core.support.RecordingPluginLogger;
import gg.modl.proto.modl.v1.HostedPlayersEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostedPlayersReporterTest {
    private final Set<UUID> online = new LinkedHashSet<>();
    private final List<HostedPlayersEvent> sent = new ArrayList<>();
    private final HostedPlayersReporter reporter = new HostedPlayersReporter(() -> new ArrayList<>(online),
            envelope -> sent.add(envelope.getHostedPlayers()), new RecordingPluginLogger());

    @Test
    void nothingIsSentBeforeTheLinkIsUp() {
        online.add(UUID.randomUUID());

        reporter.flush();

        assertTrue(sent.isEmpty());
    }

    @Test
    void aConnectSendsTheWholeListInChunksStartingWithAReplace() {
        for (int index = 0; index < HostedPlayersReporter.CHUNK_SIZE + 5; index++) online.add(UUID.randomUUID());

        reporter.connected();

        assertEquals(2, sent.size());
        assertTrue(sent.get(0).getReplace());
        assertFalse(sent.get(1).getReplace());
        assertEquals(HostedPlayersReporter.CHUNK_SIZE, sent.get(0).getJoinedUuidsCount());
        assertEquals(5, sent.get(1).getJoinedUuidsCount());
        assertEquals(HostedPlayersReporter.CHUNK_SIZE + 5, sent.get(1).getOnlineCount());
    }

    @Test
    void anEmptyServerStillReplacesTheList() {
        reporter.connected();

        assertEquals(1, sent.size());
        assertTrue(sent.get(0).getReplace());
        assertEquals(0, sent.get(0).getOnlineCount());
    }

    @Test
    void flushesSendOnlyChangesAndNothingWhenNothingChanged() {
        UUID stays = UUID.randomUUID();
        UUID leaves = UUID.randomUUID();
        online.add(stays);
        online.add(leaves);
        reporter.connected();
        sent.clear();

        reporter.flush();
        assertTrue(sent.isEmpty());

        UUID joins = UUID.randomUUID();
        online.remove(leaves);
        online.add(joins);
        reporter.flush();

        assertEquals(1, sent.size());
        assertEquals(joins.toString(), sent.get(0).getJoinedUuids(0));
        assertEquals(leaves.toString(), sent.get(0).getLeftUuids(0));
        assertTrue(sent.get(0).getSequence() > 0);
    }

    @Test
    void aReconnectReplacesTheListAgain() {
        online.add(UUID.randomUUID());
        reporter.connected();
        reporter.disconnected();
        sent.clear();

        reporter.connected();

        assertTrue(sent.get(0).getReplace());
        assertEquals(1, sent.get(0).getJoinedUuidsCount());
    }

    @Test
    void aFailingPlayerListIsReportedAtDebugAndNotThrown() {
        RecordingPluginLogger logger = new RecordingPluginLogger();
        HostedPlayersReporter failing = new HostedPlayersReporter(this::failingList, envelope -> true, logger);

        failing.connected();
        failing.flush();

        assertEquals(2, logger.debugs().size());
    }

    private Collection<UUID> failingList() {
        throw new IllegalStateException("players are being loaded");
    }
}
