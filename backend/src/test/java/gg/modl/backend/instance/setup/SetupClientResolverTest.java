package gg.modl.backend.instance.setup;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.infrastructure.web.ClientIpResolver;
import gg.modl.backend.infrastructure.web.ClientNetworks;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class SetupClientResolverTest {
    @Test
    void aDirectClientIsIdentifiedByItsAddress() {
        assertThat(resolver().resolve(request("203.0.113.7"))).isEqualTo(SetupClient.identifiedBy("203.0.113.7"));
    }

    @Test
    void anUntrustedForwardedHeaderNeitherSplitsNorAddsABudget() {
        MockHttpServletRequest request = request("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        MockHttpServletRequest other = request("127.0.0.1");
        other.addHeader("X-Forwarded-For", "198.51.100.9");

        assertThat(resolver().resolve(request))
            .isEqualTo(resolver().resolve(other))
            .isEqualTo(resolver().resolve(request("127.0.0.1")))
            .isEqualTo(SetupClient.identifiedBy("127.0.0.1"));
    }

    @Test
    void ipv6ClientsCountPerSlash64Network() {
        assertThat(ClientNetworks.keyOf("2001:db8:1:2::1"))
            .isEqualTo(ClientNetworks.keyOf("2001:db8:1:2:ffff:ffff:ffff:ffff"))
            .isNotEqualTo(ClientNetworks.keyOf("2001:db8:1:3::1"))
            .isEqualTo("20010db800010002::/64");
    }

    private static SetupClientResolver resolver() {
        return new SetupClientResolver(new ClientIpResolver());
    }

    private static MockHttpServletRequest request(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/public/setup/claim");
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
