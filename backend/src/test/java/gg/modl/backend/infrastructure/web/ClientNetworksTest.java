package gg.modl.backend.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientNetworksTest {
    @Test
    void everyAddressOfOneIpv6Slash64SharesOneKey() {
        assertThat(ClientNetworks.keyOf("2001:db8:1:2::1"))
            .isEqualTo(ClientNetworks.keyOf("2001:db8:1:2:ffff:ffff:ffff:ffff"))
            .isEqualTo(ClientNetworks.keyOf("2001:0db8:0001:0002:0:0:0:9"))
            .isNotEqualTo(ClientNetworks.keyOf("2001:db8:1:3::1"));
    }

    @Test
    void zoneIdsDoNotSplitTheNetworkKey() {
        assertThat(ClientNetworks.keyOf("fe80::1%eth0"))
            .isEqualTo(ClientNetworks.keyOf("fe80::2%no-such-interface"))
            .isEqualTo(ClientNetworks.keyOf("fe80::3"));
    }

    @Test
    void ipv4AddressesStaySeparateAndBlankAddressesShareOneKey() {
        assertThat(ClientNetworks.keyOf("203.0.113.7")).isEqualTo("203.0.113.7").isNotEqualTo(ClientNetworks.keyOf("203.0.113.8"));
        assertThat(ClientNetworks.keyOf(null)).isEqualTo(ClientNetworks.keyOf(" "));
    }

    @Test
    void theResolverKeysRequestsByTheirNetwork() {
        MockHttpServletRequest first = new MockHttpServletRequest();
        first.setRemoteAddr("2001:db8:1:2::1");
        MockHttpServletRequest second = new MockHttpServletRequest();
        second.setRemoteAddr("2001:db8:1:2::abcd");

        assertThat(new ClientIpResolver().resolveNetwork(first)).isEqualTo(new ClientIpResolver().resolveNetwork(second));
    }
}
