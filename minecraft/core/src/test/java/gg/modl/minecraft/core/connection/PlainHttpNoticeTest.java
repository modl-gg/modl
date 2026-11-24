package gg.modl.minecraft.core.connection;

import gg.modl.address.IpLiteral;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlainHttpNoticeTest {
    private final RecordingPluginLogger logger = new RecordingPluginLogger();
    private final List<String> lookups = new ArrayList<>();

    @Test
    void plainHttpToAPublicAddressWarnsOnceWithTheRemedy() {
        PlainHttpNotice notice = new PlainHttpNotice(resolvingTo("10.0.0.5", "203.0.113.7"));

        notice.check("http://modl.example.com:8080", logger);
        notice.check("http://modl.example.com:8080", logger);

        assertEquals(Collections.singletonList("remote.url http://modl.example.com:8080 sends the API key without "
                + "encryption to a public address. Use https:// (see https://github.com/modl-gg/modl/wiki/HTTPS-and-Reverse-Proxy)."),
                logger.warnings());
    }

    @Test
    void httpsNeverWarnsAndNeverResolves() {
        new PlainHttpNotice(resolvingTo("203.0.113.7")).check("https://modl.example.com", logger);

        assertTrue(logger.warnings().isEmpty());
        assertTrue(lookups.isEmpty(), lookups.toString());
    }

    @Test
    void plainHttpToNonPublicAddressesStaysQuiet() {
        PlainHttpNotice notice = new PlainHttpNotice(resolvingTo("127.0.0.1", "10.1.2.3", "100.64.1.1", "fd00::5"));

        notice.check("http://modl.internal:8080", logger);

        assertTrue(logger.warnings().isEmpty(), logger.warnings().toString());
    }

    @Test
    void aPublicLiteralWarns() {
        new PlainHttpNotice(InetAddress::getAllByName).check("http://203.0.113.7:8080", logger);

        assertEquals(1, logger.warnings().size());
    }

    @Test
    void aDnsFailurePrintsNothing() {
        new PlainHttpNotice(host -> {
            throw new UnknownHostException(host);
        }).check("http://modl.example.com", logger);

        assertTrue(logger.warnings().isEmpty());
        assertTrue(logger.infos().isEmpty());
    }

    @Test
    void anUnusableUrlPrintsNothing() {
        new PlainHttpNotice(resolvingTo("203.0.113.7")).check("not a url", logger);

        assertTrue(logger.warnings().isEmpty());
    }

    @Test
    void aQuietCheckDoesNotUseUpTheNotice() {
        PlainHttpNotice notice = new PlainHttpNotice(host -> "lan.example".equals(host)
                ? new InetAddress[]{literal("192.168.0.2")} : new InetAddress[]{literal("198.51.100.4")});

        notice.check("http://lan.example", logger);
        notice.check("http://wan.example", logger);

        assertEquals(1, logger.warnings().size());
        assertTrue(logger.warnings().get(0).startsWith("remote.url http://wan.example "));
    }

    private PlainHttpNotice.HostResolver resolvingTo(String... addresses) {
        return host -> {
            lookups.add(host);
            InetAddress[] resolved = new InetAddress[addresses.length];
            for (int i = 0; i < addresses.length; i++) resolved[i] = literal(addresses[i]);
            return resolved;
        };
    }

    private static InetAddress literal(String address) {
        return Objects.requireNonNull(IpLiteral.parseOrNull(address), address);
    }
}
