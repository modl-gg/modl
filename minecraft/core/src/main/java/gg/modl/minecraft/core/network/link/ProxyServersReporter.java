package gg.modl.minecraft.core.network.link;

import gg.modl.minecraft.core.network.ProxyBackend;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.proto.modl.v1.ProxiedServer;
import gg.modl.proto.modl.v1.ProxyServersEvent;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

final class ProxyServersReporter {
    static final long CHECK_INTERVAL_SECONDS = 10;
    static final int MAX_SERVERS = 256;
    static final int MAX_ADDRESS_LENGTH = 128;
    private static final Pattern SERVER_KEY_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private final Supplier<List<ProxyBackend>> backends;
    private final Predicate<RealtimeEnvelope> sender;
    private final PluginLogger logger;
    private @Nullable List<ProxiedServer> reported;
    private boolean connected;

    ProxyServersReporter(Supplier<List<ProxyBackend>> backends, Predicate<RealtimeEnvelope> sender, PluginLogger logger) {
        this.backends = backends;
        this.sender = sender;
        this.logger = logger;
    }

    synchronized void connected() {
        connected = true;
        reported = null;
        check();
    }

    synchronized void disconnected() {
        connected = false;
        reported = null;
    }

    synchronized void check() {
        if (!connected) return;
        try {
            Set<String> skipped = new HashSet<>();
            List<ProxiedServer> current = current(skipped);
            if (current.equals(reported)) return;
            if (!skipped.isEmpty()) logger.debug("[network] Not listing these proxy servers in the panel: " + skipped);
            if (sender.test(RealtimeEnvelope.newBuilder()
                    .setProxyServers(ProxyServersEvent.newBuilder().addAllServers(current))
                    .build())) reported = current;
        } catch (RuntimeException failure) {
            logger.debug("[network] Could not report the servers of this proxy: " + failure);
        }
    }

    private List<ProxiedServer> current(Set<String> skipped) {
        TreeMap<String, ProxiedServer> byName = new TreeMap<>();
        for (ProxyBackend backend : backends.get()) {
            String name = backend.getName();
            String key = name.toLowerCase(Locale.ROOT);
            if (!SERVER_KEY_NAME.matcher(name).matches() || byName.containsKey(key)) {
                skipped.add(name);
                continue;
            }
            byName.put(key, ProxiedServer.newBuilder().setName(name).setAddress(boundedAddress(backend.getAddress())).build());
        }
        List<ProxiedServer> servers = new ArrayList<>(byName.values());
        return servers.size() <= MAX_SERVERS ? servers : new ArrayList<>(servers.subList(0, MAX_SERVERS));
    }

    private static String boundedAddress(String address) {
        return address.getBytes(StandardCharsets.UTF_8).length <= MAX_ADDRESS_LENGTH ? address : "";
    }
}
