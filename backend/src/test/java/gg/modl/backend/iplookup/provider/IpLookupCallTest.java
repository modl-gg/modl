package gg.modl.backend.iplookup.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.iplookup.model.IpLookupAccess;
import gg.modl.backend.iplookup.model.IpLookupProvider;
import org.junit.jupiter.api.Test;

class IpLookupCallTest {
    private static final String KEY = "secret-key";

    @Test
    void urlKeyedProvidersPutTheAddressInThePathAndEncodeTheKey() {
        IpLookupCall proxycheck = new ProxycheckClient().call("203.0.113.7", keyed(IpLookupProvider.PROXYCHECK, "a&b=c"));
        IpLookupCall vpnapi = new VpnapiClient().call("2001:db8::1", keyed(IpLookupProvider.VPNAPI, KEY));

        assertThat(proxycheck.uri().toString()).isEqualTo("https://proxycheck.io/v2/203.0.113.7?vpn=1&asn=1&key=a%26b%3Dc");
        assertThat(vpnapi.uri().toString()).isEqualTo("https://vpnapi.io/api/2001:db8::1?key=secret-key");
        assertThat(proxycheck.jsonBody()).isNull();
    }

    @Test
    void iphubSendsTheKeyInAHeaderAndIpapiIsInTheBody() {
        IpLookupCall iphub = new IphubClient().call("203.0.113.7", keyed(IpLookupProvider.IPHUB, KEY));
        IpLookupCall ipapi = new IpapiIsClient().call("203.0.113.7", keyed(IpLookupProvider.IPAPI_IS, KEY));

        assertThat(iphub.uri().toString()).isEqualTo("https://v2.api.iphub.info/ip/203.0.113.7");
        assertThat(iphub.headers()).containsEntry("X-Key", KEY);
        assertThat(ipapi.uri().toString()).isEqualTo("https://api.ipapi.is/");
        assertThat(ipapi.jsonBody()).contains("\"q\":\"203.0.113.7\"").contains("\"key\":\"secret-key\"");
    }

    @Test
    void keylessProxycheckPutsTheAddressInThePathAndSendsNoKey() {
        IpLookupCall call = new KeylessProxycheckClient()
            .call("203.0.113.7", IpLookupAccess.keyless(IpLookupProvider.PROXYCHECK_KEYLESS));

        assertThat(call.uri().toString()).isEqualTo("https://proxycheck.io/v2/203.0.113.7?vpn=1&asn=1");
        assertThat(call.headers()).isEmpty();
        assertThat(call.jsonBody()).isNull();
    }

    @Test
    void anythingButAnAddressLiteralIsRefusedBeforeABuildingAUrl() {
        assertThatThrownBy(() -> new ProxycheckClient().call("203.0.113.7/../admin", keyed(IpLookupProvider.PROXYCHECK, KEY)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeylessProxycheckClient().call("203.0.113.7?key=x",
            IpLookupAccess.keyless(IpLookupProvider.PROXYCHECK_KEYLESS))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VpnapiClient().call("example.com", keyed(IpLookupProvider.VPNAPI, KEY)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IphubClient().call("fe80::1%eth0", keyed(IpLookupProvider.IPHUB, KEY)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theKeyNeverAppearsInTheTextOfACall() {
        assertThat(new VpnapiClient().call("203.0.113.7", keyed(IpLookupProvider.VPNAPI, KEY)).toString()).doesNotContain(KEY);
        assertThat(new IphubClient().call("203.0.113.7", keyed(IpLookupProvider.IPHUB, KEY)).toString()).doesNotContain(KEY);
        assertThat(keyed(IpLookupProvider.IPHUB, KEY).toString()).doesNotContain(KEY);
    }

    @Test
    void anAccessNeedsAKeyExactlyWhenItsProviderDoes() {
        assertThatThrownBy(() -> IpLookupAccess.keyless(IpLookupProvider.VPNAPI)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IpLookupAccess.keyed(IpLookupProvider.PROXYCHECK_KEYLESS, KEY))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static IpLookupAccess keyed(IpLookupProvider provider, String key) {
        return IpLookupAccess.keyed(provider, key);
    }
}
