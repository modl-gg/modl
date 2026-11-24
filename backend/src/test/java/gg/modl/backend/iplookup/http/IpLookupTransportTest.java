package gg.modl.backend.iplookup.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.iplookup.provider.IpLookupCall;
import java.net.URI;
import org.junit.jupiter.api.Test;

class IpLookupTransportTest {
    @Test
    void onlyTheBuiltInProvidersAreReachableOverHttpsOnTheDefaultPort() {
        assertThatCode(() -> IpLookupTransport.requireAllowedTarget(IpLookupCall.get(URI.create("https://vpnapi.io/api/1.1.1.1"))))
            .doesNotThrowAnyException();
        for (String refused : new String[] {
            "http://vpnapi.io/api/1.1.1.1",
            "https://vpnapi.io:8443/api/1.1.1.1",
            "https://user@vpnapi.io/api/1.1.1.1",
            "https://169.254.169.254/latest/meta-data",
            "https://vpnapi.io.example.com/api/1.1.1.1"}) {
            assertThatThrownBy(() -> IpLookupTransport.requireAllowedTarget(IpLookupCall.get(URI.create(refused))))
                .as(refused)
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void responsesAreCappedSmall() {
        assertThat(IpLookupTransport.RESPONSE_LIMIT).isEqualTo(32 * 1024);
    }
}
