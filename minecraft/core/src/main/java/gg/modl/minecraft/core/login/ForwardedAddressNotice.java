package gg.modl.minecraft.core.login;

import gg.modl.address.AddressScope;
import gg.modl.minecraft.core.util.PluginLogger;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ForwardedAddressNotice {
    private final PluginLogger logger;
    private final AtomicBoolean announced = new AtomicBoolean();

    public ForwardedAddressNotice(PluginLogger logger) {
        this.logger = logger;
    }

    public void observe(String ipAddress) {
        AddressScope scope = AddressScope.ofLiteral(ipAddress).orElse(null);
        if (scope == null || scope.isPublic() || !announced.compareAndSet(false, true)) return;
        logger.warning("Players are connecting from " + scope.name().toLowerCase(Locale.ROOT).replace('_', ' ')
                + " addresses, so their real IPs are not being forwarded. modl will not link accounts by these addresses. "
                + "If players join through a proxy, tunnel or DDoS protection, turn on its real-IP forwarding "
                + "(PROXY protocol or IP forwarding).");
    }
}
