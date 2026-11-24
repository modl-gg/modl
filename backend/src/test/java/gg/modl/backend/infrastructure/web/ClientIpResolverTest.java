package gg.modl.backend.infrastructure.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

    @Test
    void usesTheContainerResolvedRemoteAddressAndIgnoresForwardingHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.10");
        request.addHeader("X-Forwarded-For", "203.0.113.55, 10.0.0.4");
        request.addHeader("CF-Connecting-IP", "203.0.113.56");

        assertEquals("198.51.100.10", new ClientIpResolver().resolve(request));
    }
}
