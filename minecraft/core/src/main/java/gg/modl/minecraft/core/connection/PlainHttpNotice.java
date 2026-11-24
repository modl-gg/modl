package gg.modl.minecraft.core.connection;

import gg.modl.address.AddressScope;
import gg.modl.minecraft.core.util.PluginLogger;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PlainHttpNotice {
    private static final PlainHttpNotice PROCESS_WIDE = new PlainHttpNotice(InetAddress::getAllByName);

    private final HostResolver resolver;
    private final AtomicBoolean printed = new AtomicBoolean();

    public PlainHttpNotice(HostResolver resolver) {
        this.resolver = resolver;
    }

    public static PlainHttpNotice processWide() {
        return PROCESS_WIDE;
    }

    static String message(String url) {
        return ConnectionYaml.REMOTE_URL + " " + url + " sends the API key without encryption to a public address. "
                + "Use https:// (see " + WikiPages.HTTPS_AND_REVERSE_PROXY + ").";
    }

    public void check(String url, PluginLogger logger) {
        if (printed.get() || !sendsPlainTextToPublicAddress(url)) return;
        if (printed.compareAndSet(false, true)) logger.warning(message(url));
    }

    private boolean sendsPlainTextToPublicAddress(String url) {
        HttpUrl parsed;
        try {
            parsed = HttpUrl.parseBase(url);
        } catch (IllegalArgumentException unusable) {
            return false;
        }
        return HttpUrl.HTTP.equals(parsed.getScheme()) && resolvesToPublicAddress(parsed.getAddress().getHost());
    }

    private boolean resolvesToPublicAddress(String host) {
        InetAddress[] addresses;
        try {
            addresses = resolver.resolveAll(host);
        } catch (UnknownHostException | RuntimeException unresolved) {
            return false;
        }
        if (addresses == null) return false;
        for (InetAddress address : addresses) {
            if (AddressScope.classify(address).isPublic()) return true;
        }
        return false;
    }

    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolveAll(String host) throws UnknownHostException;
    }
}
