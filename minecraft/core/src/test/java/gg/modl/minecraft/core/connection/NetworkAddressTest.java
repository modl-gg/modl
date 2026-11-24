package gg.modl.minecraft.core.connection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkAddressTest {
    @Test
    void normalizesHostNamesAndIpLiterals() {
        assertEquals("modl_backend", NetworkAddress.normalizeHost(" Modl_Backend "));
        assertEquals("10.0.0.5", NetworkAddress.normalizeHost("10.0.0.5"));
        assertEquals("::1", NetworkAddress.normalizeHost("[::1]"));
        assertEquals("fe80::1%eth0", NetworkAddress.normalizeHost("fe80::1%eth0"));
        assertEquals("", NetworkAddress.normalizeHost(""));
    }

    @Test
    void rejectsMalformedHosts() {
        for (String host : new String[]{"bad host", "-leading", "trailing-", "a/b", "[not-ipv6]", "user@host",
                "256.300.1.1", "a..b", ":", "1:2:3", "1.2.3.4::", "[10.0.0.5]", "fe80::1%",
                "::1%a/b@evil.com", "::1%]:80/x", "[::1%x y]"}) {
            assertThrows(IllegalArgumentException.class, () -> NetworkAddress.normalizeHost(host), host);
        }
    }

    @Test
    void refusesIpv4SpellingsThatResolversReadDifferently() {
        for (String host : new String[]{"010.0.0.5", "127.000.000.001", "0127.0.0.1", "127.1", "1.2.3", "2130706433",
                "10.0.0.5.", "1.2.3.4.5", "0x7f000001", "0x7f.0.0.1", "127.0.0.0x1", "example.123", "0X7F.1"}) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> NetworkAddress.normalizeHost(host), host);
            assertTrue(refused.getMessage().contains("is not a valid IPv4 address"), host);
        }
    }

    @Test
    void keepsHostNamesWhoseLastLabelIsNotANumber() {
        for (String host : new String[]{"123.example.com", "0x7f.example", "mc1.host", "10.0.0.5.nip.io"}) {
            assertEquals(host, NetworkAddress.normalizeHost(host), host);
        }
    }

    @Test
    void acceptsAZoneIdOnlyWhenItIsMadeOfUnreservedCharacters() {
        assertEquals("fe80::1%enp0s31f6", NetworkAddress.normalizeHost("FE80::1%ENP0S31F6"));
        assertEquals("fe80::1%br-1a2b.3~c_d", NetworkAddress.normalizeHost("[fe80::1%br-1a2b.3~c_d]"));
        for (String host : new String[]{"fe80::1%eth0%1", "fe80::1%eth0:1", "[fe80::1%25]x"}) {
            assertThrows(IllegalArgumentException.class, () -> NetworkAddress.normalizeHost(host), host);
        }
    }

    @Test
    void explainsAHostThatCarriesAPort() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> NetworkAddress.normalizeHost("10.0.0.5:25590"))
                .getMessage().contains("put the port in its own setting"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> NetworkAddress.normalizeHost("proxy:25590"))
                .getMessage().contains("put the port in its own setting"));
    }

    @Test
    void recognisesEveryLoopbackAndWildcardSpelling() {
        for (String loopback : new String[]{"0::1", "::ffff:127.0.0.1", "localhost.", "0:0:0:0:0:0:0:1", "::1%lo"}) {
            assertTrue(NetworkAddress.of(loopback, 80).isLoopback(), loopback);
        }
        for (String wildcard : new String[]{"::0", "0:0::0", "::ffff:0.0.0.0", "0.0.0.0"}) {
            assertTrue(NetworkAddress.of(wildcard, 80).isWildcard(), wildcard);
        }
    }

    @Test
    void classifiesWildcardAndLoopbackHosts() {
        assertTrue(NetworkAddress.of("0.0.0.0", 8080).isWildcard());
        assertTrue(NetworkAddress.of("::", 8080).isWildcard());
        assertTrue(NetworkAddress.of("127.0.0.1", 8080).isLoopback());
        assertTrue(NetworkAddress.of("127.1.2.3", 8080).isLoopback());
        assertTrue(NetworkAddress.of("localhost", 8080).isLoopback());
        assertTrue(NetworkAddress.of("[::1]", 8080).isLoopback());
        assertFalse(NetworkAddress.of("10.0.0.5", 8080).isWildcard());
        assertFalse(NetworkAddress.of("10.0.0.5", 8080).isLoopback());
        assertFalse(NetworkAddress.of("127.example.com", 8080).isLoopback());
    }

    @Test
    void connectsToLoopbackWhenBoundToEveryInterface() {
        assertEquals("127.0.0.1", NetworkAddress.of("0.0.0.0", 8080).connectHost());
        assertEquals("10.0.0.5", NetworkAddress.of("10.0.0.5", 8080).connectHost());
        assertEquals("[::1]:8080", NetworkAddress.of("::1", 8080).authority());
    }

    @Test
    void validatesThePortRange() {
        assertTrue(NetworkAddress.isValidPort(1));
        assertTrue(NetworkAddress.isValidPort(65535));
        assertFalse(NetworkAddress.isValidPort(0));
        assertFalse(NetworkAddress.isValidPort(65536));
        assertThrows(IllegalArgumentException.class, () -> NetworkAddress.of("host", 0));
    }
}
