package gg.modl.minecraft.core.network;

import lombok.Value;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

@Value
public class ProxyBackend {
    String name;
    String address;

    public static ProxyBackend of(String name, SocketAddress address) {
        return new ProxyBackend(name, describe(address));
    }

    private static String describe(SocketAddress address) {
        if (!(address instanceof InetSocketAddress)) return String.valueOf(address);
        InetSocketAddress inet = (InetSocketAddress) address;
        String host = inet.getHostString();
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + inet.getPort();
    }
}
