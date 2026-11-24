package gg.modl.minecraft.core.network;

import gg.modl.minecraft.core.util.PluginLogger;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class ProxyServerNames {
    private final Supplier<Set<String>> knownNames;
    private final PluginLogger logger;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public ProxyServerNames(Supplier<Set<String>> knownNames, PluginLogger logger) {
        this.knownNames = knownNames;
        this.logger = logger;
    }

    public static String unknownNameWarning(String serverName, Set<String> known) {
        return "[network] " + serverName + " is not a server name in the proxy config (known: "
                + String.join(", ", new TreeSet<>(known))
                + "); staff hotbar actions from it are refused until the names match.";
    }

    public void warnOnceIfUnknown(String serverName) {
        Set<String> known = knownNames.get();
        for (String name : known) {
            if (name.equalsIgnoreCase(serverName)) return;
        }
        if (warned.add(serverName.toLowerCase(Locale.ROOT))) logger.warning(unknownNameWarning(serverName, known));
    }
}
