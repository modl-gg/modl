package gg.modl.backend.infrastructure.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IpAddressesTest {
    @ParameterizedTest
    @ValueSource(strings = {"203.0.113.7", "::1", "2001:db8::7", "fe80::1%eth0", "::ffff:203.0.113.7"})
    void anAddressLiteralIsKept(String address) {
        assertEquals(address, IpAddresses.literalOrNull(address));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "unknown", "hello", "localhost", "999.1.1.1", "203.0.113.7:25565"})
    void anythingElseIsDropped(String address) {
        assertNull(IpAddresses.literalOrNull(address));
    }

    @Test
    void theLiteralComesBackTrimmedSoEveryStoreKeysTheSameText() {
        assertEquals("203.0.113.7", IpAddresses.literalOrNull("  203.0.113.7\t"));
        assertEquals("2001:db8::7", IpAddresses.literalOrNull(" 2001:db8::7 "));
    }

    @Test
    void nullIsNotALiteral() {
        assertNull(IpAddresses.literalOrNull(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "1.2.3.4", "192.168.1.1", "255.255.255.255", "10.0.0.255"})
    void acceptsIpv4(String ip) {
        assertTrue(IpAddresses.isLiteral(ip));
    }

    @ParameterizedTest
    @ValueSource(strings = {"::", "::1", "fe80::1", "2001:db8::1", "2001:0db8:0000:0000:0000:0000:0000:0001",
        "::ffff:192.168.1.1", "1:2:3:4:5:6:192.168.1.1"})
    void acceptsIpv6(String ip) {
        assertTrue(IpAddresses.isLiteral(ip));
    }

    @ParameterizedTest
    @ValueSource(strings = {"example.com", "player.mojang.com", "not-an-ip"})
    void rejectsHostnames(String hostname) {
        assertFalse(IpAddresses.isLiteral(hostname));
    }

    @ParameterizedTest
    @ValueSource(strings = {"192.168.01.1", "01.02.03.04", "192.168.001.1"})
    void rejectsLeadingZeroOctets(String ip) {
        assertFalse(IpAddresses.isLiteral(ip));
    }

    @ParameterizedTest
    @ValueSource(strings = {"256.1.1.1", "1.2.3.256", "999.999.999.999", "1.2.3", "1.2.3.4.5"})
    void rejectsOutOfRangeOrMalformedIpv4(String ip) {
        assertFalse(IpAddresses.isLiteral(ip));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1::2::3", ":::", "12345::", "gggg::1", "1:2:3:4:5:6:7:8:9", "1.2.3.4::1", "%eth0"})
    void rejectsMalformedIpv6(String ip) {
        assertFalse(IpAddresses.isLiteral(ip));
    }
}
