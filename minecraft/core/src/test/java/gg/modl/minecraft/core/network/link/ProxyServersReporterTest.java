package gg.modl.minecraft.core.network.link;

import gg.modl.minecraft.core.network.ProxyBackend;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import gg.modl.proto.modl.v1.ProxiedServer;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyServersReporterTest {
    private final List<ProxyBackend> backends = new ArrayList<>();
    private final List<RealtimeEnvelope> sent = new ArrayList<>();
    private final AtomicBoolean deliverable = new AtomicBoolean(true);
    private final ProxyServersReporter reporter = new ProxyServersReporter(() -> new ArrayList<>(backends),
            envelope -> deliverable.get() && sent.add(envelope), new RecordingPluginLogger());

    @Test
    void theListIsSentOnConnectAndAgainOnlyWhenItChanges() {
        backends.add(new ProxyBackend("survival", "10.0.0.3:25565"));
        backends.add(new ProxyBackend("lobby", "10.0.0.2:25565"));

        reporter.check();
        assertTrue(sent.isEmpty());

        reporter.connected();
        reporter.check();
        assertEquals(1, sent.size());
        assertEquals(Arrays.asList("lobby", "survival"), names(sent.get(0)));

        backends.add(new ProxyBackend("minigames", "10.0.0.4:25565"));
        reporter.check();
        reporter.check();
        assertEquals(2, sent.size());
        assertEquals(Arrays.asList("lobby", "minigames", "survival"), names(sent.get(1)));

        reporter.disconnected();
        reporter.connected();
        assertEquals(3, sent.size());
    }

    @Test
    void anUndeliveredListIsSentAtTheNextCheck() {
        backends.add(new ProxyBackend("lobby", "10.0.0.2:25565"));
        deliverable.set(false);
        reporter.connected();
        deliverable.set(true);
        reporter.check();
        reporter.check();

        assertEquals(1, sent.size());
    }

    @Test
    void namesOutsideTheServerKeyRuleDuplicatesAndLongAddressesAreBounded() {
        backends.add(new ProxyBackend("Lobby", "10.0.0.2:25565"));
        backends.add(new ProxyBackend("lobby", "10.0.0.9:25565"));
        backends.add(new ProxyBackend("event server", "10.0.0.5:25565"));
        backends.add(new ProxyBackend("far", repeat('a', ProxyServersReporter.MAX_ADDRESS_LENGTH + 1)));
        for (int index = 0; index < ProxyServersReporter.MAX_SERVERS; index++) backends.add(new ProxyBackend("s" + index, ""));

        reporter.connected();

        List<ProxiedServer> servers = sent.get(0).getProxyServers().getServersList();
        assertEquals(ProxyServersReporter.MAX_SERVERS, servers.size());
        assertEquals("far", servers.get(0).getName());
        assertEquals("", servers.get(0).getAddress());
        assertEquals("Lobby", servers.get(1).getName());
        assertEquals("10.0.0.2:25565", servers.get(1).getAddress());
    }

    @Test
    void addressesAreHostAndPortWithoutALookup() throws UnknownHostException {
        byte[] loopback = new byte[16];
        loopback[15] = 1;
        assertEquals("lobby:25565", ProxyBackend.of("lobby", InetSocketAddress.createUnresolved("lobby", 25565)).getAddress());
        assertEquals("[0:0:0:0:0:0:0:1]:25566",
                ProxyBackend.of("local", new InetSocketAddress(InetAddress.getByAddress(loopback), 25566)).getAddress());
    }

    private static List<String> names(RealtimeEnvelope envelope) {
        List<String> names = new ArrayList<>();
        for (ProxiedServer server : envelope.getProxyServers().getServersList()) names.add(server.getName());
        return names;
    }

    private static String repeat(char character, int count) {
        StringBuilder text = new StringBuilder(count);
        for (int index = 0; index < count; index++) text.append(character);
        return text.toString();
    }
}
