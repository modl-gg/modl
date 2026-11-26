package gg.modl.backend.infrastructure.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class HttpOriginTest {

    @Test
    void parseExactNormalizesSchemeHostAndDefaultPort() {
        assertEquals("https://panel.example.com", HttpOrigin.parseExact("HTTPS://Panel.Example.com:443/").orElseThrow().value());
        assertEquals("http://192.168.1.5:8080", HttpOrigin.parseExact("http://192.168.1.5:8080").orElseThrow().value());
    }

    @Test
    void parseExactRejectsPathsQueriesCredentialsAndOtherSchemes() {
        assertTrue(HttpOrigin.parseExact("https://panel.example.com/modl").isEmpty());
        assertTrue(HttpOrigin.parseExact("https://panel.example.com/?x=1").isEmpty());
        assertTrue(HttpOrigin.parseExact("https://user:secret@panel.example.com").isEmpty());
        assertTrue(HttpOrigin.parseExact("ftp://panel.example.com").isEmpty());
        assertTrue(HttpOrigin.parseExact("javascript:alert(1)").isEmpty());
        assertTrue(HttpOrigin.parseExact("").isEmpty());
        assertTrue(HttpOrigin.parseExact(null).isEmpty());
    }

    @Test
    void parseIgnoresPathsSoRefererHeadersCanBeCompared() {
        assertEquals(HttpOrigin.parseExact("https://panel.example.com").orElseThrow(),
            HttpOrigin.parse("https://panel.example.com/panel/settings?tab=general").orElseThrow());
    }

    @Test
    void acceptsHostNamesWithUnderscoresLikeDockerServiceNames() {
        assertEquals("http://modl_server:8080", HttpOrigin.parseExact("http://modl_server:8080").orElseThrow().value());
        assertEquals("https://my_panel.example.com",
            HttpOrigin.parse("https://My_Panel.example.com/panel/tickets").orElseThrow().value());
    }

    @Test
    void acceptsBracketedIpv6Literals() {
        assertEquals("http://[::1]:8080", HttpOrigin.parseExact("http://[::1]:8080").orElseThrow().value());
        assertEquals("https://[2001:db8::7]", HttpOrigin.parseExact("https://[2001:db8::7]:443").orElseThrow().value());
    }

    @Test
    void rejectsPortsOutsideTheValidRange() {
        assertTrue(HttpOrigin.parseExact("http://panel.example.com:0").isEmpty());
        assertTrue(HttpOrigin.parseExact("http://panel.example.com:65536").isEmpty());
        assertTrue(HttpOrigin.parseExact("http://panel.example.com:99999999999").isEmpty());
        assertTrue(HttpOrigin.parseExact("http://panel.example.com:80a").isEmpty());
        assertEquals(65_535, HttpOrigin.parseExact("http://panel.example.com:65535").orElseThrow().port());
    }

    @Test
    void rejectsMalformedHosts() {
        assertTrue(HttpOrigin.parseExact("http://").isEmpty());
        assertTrue(HttpOrigin.parseExact("http://pa nel.example.com").isEmpty());
        assertTrue(HttpOrigin.parseExact("http://::1:8080").isEmpty());
        assertTrue(HttpOrigin.parseExact("http://[::1").isEmpty());
        assertTrue(HttpOrigin.parseExact("http://[zz::1]").isEmpty());
    }

    @Test
    void requireExactExplainsWhatIsWrong() {
        assertEquals("has port 70000, which is outside 1 to 65535",
            assertThrows(IllegalArgumentException.class, () -> HttpOrigin.requireExact("http://panel.example.com:70000")).getMessage());
        assertEquals("must start with http:// or https://",
            assertThrows(IllegalArgumentException.class, () -> HttpOrigin.requireExact("panel.example.com")).getMessage());
        assertEquals("must be only scheme, host, and port, without a path, query, or fragment",
            assertThrows(IllegalArgumentException.class, () -> HttpOrigin.requireExact("https://panel.example.com/modl")).getMessage());
        assertEquals("must not contain a user name or password",
            assertThrows(IllegalArgumentException.class, () -> HttpOrigin.requireExact("https://u:p@panel.example.com")).getMessage());
        assertEquals("is empty", assertThrows(IllegalArgumentException.class, () -> HttpOrigin.requireExact(" ")).getMessage());
        assertEquals("https://panel.example.com", HttpOrigin.requireExact(" https://panel.example.com/ ").value());
    }

    @Test
    void requestOriginForIpv6HostsAgreesWithParsedOrigins() {
        MockHttpServletRequest bracketed = new MockHttpServletRequest();
        bracketed.setScheme("http");
        bracketed.setServerName("[::1]");
        bracketed.setServerPort(8080);
        MockHttpServletRequest bare = new MockHttpServletRequest();
        bare.setScheme("http");
        bare.setServerName("::1");
        bare.setServerPort(8080);

        HttpOrigin parsed = HttpOrigin.parseExact("http://[::1]:8080").orElseThrow();
        assertEquals(parsed, HttpOrigin.of(bracketed));
        assertEquals(parsed, HttpOrigin.of(bare));
    }

    @Test
    void recognizesLoopbackHosts() {
        assertTrue(HttpOrigin.parseExact("http://localhost:8080").orElseThrow().isLoopback());
        assertTrue(HttpOrigin.parseExact("http://panel.localhost").orElseThrow().isLoopback());
        assertTrue(HttpOrigin.parseExact("http://127.0.0.1:8080").orElseThrow().isLoopback());
        assertTrue(HttpOrigin.parseExact("http://127.1.2.3").orElseThrow().isLoopback());
        assertTrue(HttpOrigin.parseExact("http://[::1]:8080").orElseThrow().isLoopback());
        assertFalse(HttpOrigin.parseExact("http://127.example.com").orElseThrow().isLoopback());
        assertFalse(HttpOrigin.parseExact("https://panel.example.com").orElseThrow().isLoopback());
        assertFalse(HttpOrigin.parseExact("http://192.168.1.5:8080").orElseThrow().isLoopback());
    }

    @Test
    void ofUsesTheEffectiveRequestSchemeHostAndPort() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(8080);

        assertEquals("http://localhost:8080", HttpOrigin.of(request).value());
    }
}
