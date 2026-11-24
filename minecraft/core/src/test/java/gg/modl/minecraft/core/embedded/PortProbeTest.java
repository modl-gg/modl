package gg.modl.minecraft.core.embedded;

import gg.modl.minecraft.core.connection.NetworkAddress;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortProbeTest {
    @Test
    void reportsAFreePortAsAvailable() throws IOException {
        int port = freePort();

        assertEquals(PortProbe.Outcome.AVAILABLE, PortProbe.probe(NetworkAddress.of("127.0.0.1", port)));
    }

    @Test
    void distinguishesAPortInUseFromAnUnusableAddress() throws IOException {
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            NetworkAddress taken = NetworkAddress.of("127.0.0.1", occupied.getLocalPort());

            assertEquals(PortProbe.Outcome.PORT_IN_USE, PortProbe.probe(taken));
            EmbeddedServerException failure = assertThrows(EmbeddedServerException.class,
                    () -> PortProbe.requireAvailable(taken, "plugins/modl/connection.yml"));
            assertTrue(failure.getMessage().contains("already in use"));
            assertTrue(failure.getMessage().contains("embedded.port"));
        }
    }

    @Test
    void reportsAnAddressThatDoesNotBelongToThisMachine() throws IOException {
        NetworkAddress foreign = NetworkAddress.of("203.0.113.7", freePort());

        assertEquals(PortProbe.Outcome.FOREIGN_ADDRESS, PortProbe.probe(foreign));
        assertTrue(assertThrows(EmbeddedServerException.class,
                () -> PortProbe.requireAvailable(foreign, "plugins/modl/connection.yml")).getMessage()
                .contains("embedded.bind-address"));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
