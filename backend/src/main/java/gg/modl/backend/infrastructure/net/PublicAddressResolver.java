package gg.modl.backend.infrastructure.net;

import gg.modl.address.AddressScope;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import org.apache.hc.client5.http.DnsResolver;

public final class PublicAddressResolver implements DnsResolver {
    private final HostLookup lookup;

    public PublicAddressResolver(HostLookup lookup) {
        this.lookup = lookup;
    }

    public static PublicAddressResolver usingSystemDns() {
        return new PublicAddressResolver(HostLookup.SYSTEM);
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = lookup.lookup(host);
        if (addresses.length == 0) {
            throw new UnknownHostException(host);
        }
        for (InetAddress address : addresses) {
            if (!AddressScope.classify(address).isPublic()) {
                throw new NonPublicAddressException(host);
            }
        }
        return addresses.clone();
    }

    @Override
    public List<InetSocketAddress> resolve(String host, int port) throws UnknownHostException {
        return Arrays.stream(resolve(host)).map(address -> new InetSocketAddress(address, port)).toList();
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        return host;
    }

    @FunctionalInterface
    public interface HostLookup {
        HostLookup SYSTEM = InetAddress::getAllByName;

        InetAddress[] lookup(String host) throws UnknownHostException;
    }

    public static final class NonPublicAddressException extends UnknownHostException {
        public NonPublicAddressException(String host) {
            super(host + " resolved to a non-public address");
        }
    }
}
