package gg.modl.minecraft.core.network.link;

import gg.modl.proto.modl.v1.HostedPlayersEvent;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostedPlayerCountReporterTest {
    private final AtomicInteger online = new AtomicInteger(2);
    private final List<RealtimeEnvelope> sent = new ArrayList<>();
    private final HostedPlayerCountReporter reporter = new HostedPlayerCountReporter(online::get, sent::add);

    @Test
    void aMemberSendsOnlyItsCountAndOnlyWhenItChanges() {
        reporter.flush();
        assertTrue(sent.isEmpty());

        reporter.connected();
        reporter.flush();
        online.set(3);
        reporter.flush();

        assertEquals(Arrays.asList(2, 3), counts());
        for (RealtimeEnvelope envelope : sent) {
            HostedPlayersEvent event = envelope.getHostedPlayers();
            assertEquals(0, event.getJoinedUuidsCount());
            assertEquals(0, event.getLeftUuidsCount());
        }
    }

    @Test
    void aReconnectReportsTheCountAgain() {
        reporter.connected();
        reporter.disconnected();
        reporter.flush();
        reporter.connected();

        assertEquals(Arrays.asList(2, 2), counts());
    }

    private List<Integer> counts() {
        List<Integer> counts = new ArrayList<>();
        for (RealtimeEnvelope envelope : sent) counts.add(envelope.getHostedPlayers().getOnlineCount());
        return counts;
    }
}
