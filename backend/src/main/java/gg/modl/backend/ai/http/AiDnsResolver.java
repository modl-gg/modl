package gg.modl.backend.ai.http;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import org.apache.hc.client5.http.DnsResolver;

final class AiDnsResolver implements DnsResolver {
    private final AiAddressPolicy policy;
    private final HostLookup lookup;
    private final boolean plainHttp;

    AiDnsResolver(AiAddressPolicy policy, HostLookup lookup, boolean plainHttp) {
        this.policy = policy;
        this.lookup = lookup;
        this.plainHttp = plainHttp;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        String name = unbracketed(host);
        InetAddress[] addresses = lookup.lookup(name);
        if (addresses.length == 0) {
            throw new UnknownHostException(name);
        }
        for (InetAddress address : addresses) {
            if (policy.check(address, plainHttp) != AiAddressPolicy.Verdict.ALLOWED) {
                throw new AiBlockedAddressException(name);
            }
        }
        return addresses.clone();
    }

    @Override
    public List<InetSocketAddress> resolve(String host, int port) throws UnknownHostException {
        return Arrays.stream(resolve(host)).map(address -> new InetSocketAddress(address, port)).toList();
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        return unbracketed(host);
    }

    private static String unbracketed(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    @FunctionalInterface
    interface HostLookup {
        HostLookup SYSTEM = InetAddress::getAllByName;

        InetAddress[] lookup(String host) throws UnknownHostException;
    }
}
