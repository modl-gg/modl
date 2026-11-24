package gg.modl.address;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddressScopeTest {
    private static final String FIXTURE = "/gg/modl/address/address-scopes.txt";
    private static final String PUBLIC = "PUBLIC";

    @Test
    void classifiesIpv4Ranges() {
        assertScope(AddressScope.LOOPBACK, "127.0.0.1", "127.255.0.9");
        assertScope(AddressScope.PRIVATE, "10.0.0.1", "172.16.0.1", "172.17.0.2", "172.31.255.255", "192.168.1.20");
        assertScope(AddressScope.CARRIER_GRADE_NAT, "100.64.0.1", "100.127.255.254");
        assertScope(AddressScope.LINK_LOCAL, "169.254.10.20");
        assertScope(AddressScope.UNSPECIFIED, "0.0.0.0");
        assertScope(AddressScope.RESERVED, "224.0.0.1", "240.0.0.1", "198.18.0.1", "192.0.0.8", "0.1.2.3");
        assertScope(AddressScope.PUBLIC, "8.8.8.8", "203.0.113.7", "172.32.0.1", "100.63.255.255", "100.128.0.1",
                "192.169.0.1");
    }

    @Test
    void classifiesIpv6Ranges() {
        assertScope(AddressScope.LOOPBACK, "::1");
        assertScope(AddressScope.UNSPECIFIED, "::");
        assertScope(AddressScope.LINK_LOCAL, "fe80::1", "fe80::1%eth0");
        assertScope(AddressScope.UNIQUE_LOCAL, "fc00::1", "fd12:3456::1", "FD00::A");
        assertScope(AddressScope.RESERVED, "ff02::1");
        assertScope(AddressScope.PUBLIC, "2001:4860:4860::8888", "2606:4700:4700::1111", "fe00::1");
    }

    @Test
    void embeddedIpv4AddressesAreClassifiedAsIpv4() {
        assertScope(AddressScope.PRIVATE, "::ffff:192.168.0.1", "::ffff:10.0.0.1", "2002:0a00:0001::1");
        assertScope(AddressScope.LOOPBACK, "64:ff9b::7f00:1");
        assertScope(AddressScope.PUBLIC, "::ffff:8.8.8.8", "2002:0808:0808::1", "64:ff9b::808:808");
    }

    @Test
    void onlyPublicIsPublic() {
        for (AddressScope scope : AddressScope.values()) {
            assertEquals(scope == AddressScope.PUBLIC, scope.isPublic(), scope.name());
        }
    }

    @Test
    void anythingButAnAddressLiteralHasNoScopeAndIsNeverLookedUp() {
        for (String notALiteral : new String[]{null, "", "  ", "modl.example.com", "example.com", "localhost", "999.1.1.1",
                "300.1.1.1", "1.2.3", "010.0.0.1", "1::2::3", "1.2.3.4%eth0"}) {
            assertFalse(AddressScope.ofLiteral(notALiteral).isPresent(), String.valueOf(notALiteral));
            assertFalse(AddressScope.isPublicLiteral(notALiteral), String.valueOf(notALiteral));
        }
    }

    @Test
    void everyAddressInTheFixtureIsClassifiedAsListed() throws IOException {
        List<String[]> cases = fixtureCases();
        assertFalse(cases.isEmpty());
        for (String[] entry : cases) {
            Optional<AddressScope> scope = AddressScope.ofLiteral(entry[0]);
            assertTrue(scope.isPresent(), entry[0]);
            assertEquals(PUBLIC.equals(entry[1]), scope.get().isPublic(), entry[0]);
        }
    }

    private static void assertScope(AddressScope expected, String... literals) {
        for (String literal : literals) {
            Optional<AddressScope> scope = AddressScope.ofLiteral(literal);
            assertTrue(scope.isPresent(), literal);
            assertEquals(expected, scope.get(), literal);
        }
    }

    private static List<String[]> fixtureCases() throws IOException {
        InputStream stream = AddressScopeTest.class.getResourceAsStream(FIXTURE);
        assertNotNull(stream, FIXTURE);
        List<String[]> cases = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) cases.add(line.trim().split(" "));
            }
        }
        return cases;
    }
}
