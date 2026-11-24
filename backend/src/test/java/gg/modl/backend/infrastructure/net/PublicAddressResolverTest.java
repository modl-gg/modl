package gg.modl.backend.infrastructure.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublicAddressResolverTest {
    @Test
    void aNameResolvingToAnyNonPublicAddressIsRefused() throws UnknownHostException {
        InetAddress internal = InetAddress.getByName("10.0.0.5");
        InetAddress publicAddress = InetAddress.getByName("149.137.128.1");
        PublicAddressResolver rebinding = new PublicAddressResolver(host -> new InetAddress[] {publicAddress, internal});
        PublicAddressResolver honest = new PublicAddressResolver(host -> new InetAddress[] {publicAddress});

        assertThatThrownBy(() -> rebinding.resolve("example.com")).isInstanceOf(PublicAddressResolver.NonPublicAddressException.class);
        assertThat(honest.resolve("example.com")).containsExactly(publicAddress);
    }

    @Test
    void anEmptyAnswerIsAnUnknownHost() {
        PublicAddressResolver empty = new PublicAddressResolver(host -> new InetAddress[0]);

        assertThatThrownBy(() -> empty.resolve("example.com")).isInstanceOf(UnknownHostException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::ffff:127.0.0.1", "100.64.0.1", "fd00::1", "169.254.169.254", "64:ff9b::a00:5"})
    void everyNonPublicScopeIsRefused(String literal) throws UnknownHostException {
        InetAddress address = InetAddress.getByName(literal);
        PublicAddressResolver resolver = new PublicAddressResolver(host -> new InetAddress[] {address});

        assertThatThrownBy(() -> resolver.resolve("example.com")).as(literal)
            .isInstanceOf(PublicAddressResolver.NonPublicAddressException.class);
    }
}
