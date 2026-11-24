package gg.modl.backend.ai.http;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.ai.http.AiAddressPolicy.Verdict;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AiAddressPolicyTest {
    private final AiAddressPolicy policy = new AiAddressPolicy();

    @ParameterizedTest
    @ValueSource(strings = {
        "0.0.0.0", "0.255.255.255", "224.0.0.1", "239.255.255.255", "240.0.0.1", "255.255.255.255",
        "169.254.0.1", "169.254.169.254", "100.100.100.200", "168.63.129.16", "192.0.0.192",
        "::", "ff02::1", "fe80::1", "febf::1", "fd00:ec2::254", "fd20:ce::254", "2001:0:4136:e378::1",
        "::ffff:169.254.169.254", "64:ff9b::a9fe:a9fe", "2002:a9fe:a9fe::1", "::a9fe:a9fe"
    })
    void blockedAddressesAreRefusedForEveryScheme(String address) throws UnknownHostException {
        assertThat(policy.check(InetAddress.getByName(address), false)).isEqualTo(Verdict.BLOCKED);
        assertThat(policy.check(InetAddress.getByName(address), true)).isEqualTo(Verdict.BLOCKED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "127.0.0.1", "127.255.255.254", "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.255", "192.168.1.10",
        "100.64.0.1", "100.127.255.255", "::1", "fc00::1", "fdff::1", "::ffff:10.0.0.1", "64:ff9b::a00:1"
    })
    void privateAddressesAllowPlainHttp(String address) throws UnknownHostException {
        assertThat(policy.check(InetAddress.getByName(address), true)).isEqualTo(Verdict.ALLOWED);
        assertThat(policy.check(InetAddress.getByName(address), false)).isEqualTo(Verdict.ALLOWED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "8.8.8.8", "1.1.1.1", "172.15.255.255", "172.32.0.1", "100.63.255.255", "100.128.0.1", "169.253.255.255",
        "2606:4700::1111", "2002:808:808::1", "::ffff:8.8.8.8"
    })
    void publicAddressesNeedHttps(String address) throws UnknownHostException {
        assertThat(policy.check(InetAddress.getByName(address), true)).isEqualTo(Verdict.PLAIN_HTTP_PUBLIC);
        assertThat(policy.check(InetAddress.getByName(address), false)).isEqualTo(Verdict.ALLOWED);
    }
}
