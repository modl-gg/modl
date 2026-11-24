package gg.modl.address;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpLiteralTest {
    @Test
    void literalsOfBothFamiliesAreRecognised() {
        for (String literal : new String[]{"1.2.3.4", "255.255.255.255", "::", "::1", "fe80::1%eth0", "2001:db8::1",
                "::ffff:192.0.2.1", "1:2:3:4:5:6:7:8"}) {
            assertTrue(IpLiteral.isLiteral(literal), literal);
        }
    }

    @Test
    void namesAndMalformedAddressesAreNotLiterals() {
        for (String value : new String[]{"", "localhost", "example.com", "1.2.3", "1.2.3.4.5", "256.1.1.1", "01.2.3.4",
                "1::2::3", "1:2:3:4:5:6:7:8:9", "12345::1", "::1.2.3", "1.2.3.4%eth0"}) {
            assertFalse(IpLiteral.isLiteral(value), value);
        }
    }

    @Test
    void anOctetWithALeadingZeroIsNotALiteralBecauseResolversReadItAsOctal() {
        for (String value : new String[]{"010.0.0.1", "127.000.000.001", "1.2.3.04", "::ffff:010.0.0.1"}) {
            assertFalse(IpLiteral.isLiteral(value), value);
        }
        assertTrue(IpLiteral.isIpv4Literal("10.0.0.0"));
    }

    @Test
    void aZoneIdIsNonEmptyAndMadeOfUnreservedCharacters() {
        for (String zoned : new String[]{"fe80::1%eth0", "fe80::1%ENP0S31F6", "fe80::1%12", "fe80::1%br-1a.2~b_c"}) {
            assertTrue(IpLiteral.isIpv6Literal(zoned), zoned);
        }
        for (String zoned : new String[]{"fe80::1%", "::1%a/b@evil.com", "::1%]:80/x", "::1%x y", "fe80::1%eth0%1",
                "fe80::1%eth0:1"}) {
            assertFalse(IpLiteral.isLiteral(zoned), zoned);
            assertNull(IpLiteral.parseOrNull(zoned), zoned);
        }
    }

    @Test
    void eachFamilyCheckAcceptsOnlyItsOwnFamily() {
        assertTrue(IpLiteral.isIpv4Literal("192.0.2.1"));
        assertFalse(IpLiteral.isIpv4Literal("::1"));
        assertTrue(IpLiteral.isIpv6Literal("::1"));
        assertFalse(IpLiteral.isIpv6Literal("192.0.2.1"));
    }

    @Test
    void theZoneIsDroppedOnlyFromAnAddressThatHasOne() {
        assertEquals("fe80::1", IpLiteral.withoutZone("fe80::1%eth0"));
        assertEquals("10.0.0.1", IpLiteral.withoutZone("10.0.0.1"));
    }

    @Test
    void onlyLiteralsAreParsedSoNoNameIsEverResolved() {
        assertNotNull(IpLiteral.parseOrNull(" 10.0.0.1 "));
        assertNotNull(IpLiteral.parseOrNull("fe80::1%eth0"));
        assertNull(IpLiteral.parseOrNull("localhost"));
        assertNull(IpLiteral.parseOrNull(null));
    }

    @Test
    void anIpv6AddressIsDisplayedInItsCompressedForm() {
        String[][] cases = {
            {"0:0:0:0:0:0:0:1", "::1"},
            {"0:0:0:0:0:0:0:0", "::"},
            {"2001:0DB8:0000:0000:0000:0000:0000:0001", "2001:db8::1"},
            {"2001:db8:0:0:1:0:0:1", "2001:db8::1:0:0:1"},
            {"1:0:0:2:0:0:0:0", "1:0:0:2::"},
            {"2001:db8:0:1:1:1:1:1", "2001:db8:0:1:1:1:1:1"},
            {"0:0:0:0:0:ffff:c000:201", "::ffff:192.0.2.1"},
            {"fe80:0:0:0:0:0:0:1%eth0", "fe80::1%eth0"},
        };
        for (String[] pair : cases) {
            assertEquals(pair[1], IpLiteral.display(pair[0]), pair[0]);
        }
    }

    @Test
    void anythingButAnIpv6LiteralIsDisplayedUnchanged() {
        for (String value : new String[]{"192.0.2.1", "localhost", "", "not an address", " ::1"}) {
            assertEquals(value, IpLiteral.display(value), value);
        }
    }
}
