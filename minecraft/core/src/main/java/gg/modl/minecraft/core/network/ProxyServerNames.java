package gg.modl.minecraft.core.network;

import gg.modl.minecraft.core.util.PluginLogger;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class ProxyServerNames {
    private final Supplier<List<ProxyBackend>> backends;
    private final PluginLogger logger;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public ProxyServerNames(Supplier<List<ProxyBackend>> backends, PluginLogger logger) {
        this.backends = backends;
        this.logger = logger;
    }

    public static String unknownNameWarning(String serverName, Set<String> known) {
        return "[network] " + serverName + " is not a server name in the proxy config (known: "
                + String.join(", ", new TreeSet<>(known))
                + "); staff hotbar actions from it are refused until the names match.";
    }

    public void warnOnceAboutUnknown(Collection<String> serverNames) {
        if (serverNames.isEmpty()) return;
        Set<String> known = new TreeSet<>();
        Set<String> knownLowerCase = new HashSet<>();
        for (ProxyBackend backend : backends.get()) {
            known.add(backend.getName());
            knownLowerCase.add(backend.getName().toLowerCase(Locale.ROOT));
        }
        for (String serverName : serverNames) {
            String lowerCase = serverName.toLowerCase(Locale.ROOT);
            if (!knownLowerCase.contains(lowerCase) && warned.add(lowerCase)) logger.warning(unknownNameWarning(serverName, known));
        }
    }
}
