package gg.modl.minecraft.core.network;

import java.util.concurrent.atomic.AtomicReference;

public final class SwitchableNetworkAvailability implements NetworkAvailability {
    private final NetworkAvailability unlinked;
    private final AtomicReference<NetworkAvailability> current;

    public SwitchableNetworkAvailability(NetworkAvailability unlinked) {
        this.unlinked = unlinked;
        this.current = new AtomicReference<>(unlinked);
    }

    public Runnable use(NetworkAvailability availability) {
        current.set(availability);
        return () -> current.compareAndSet(availability, unlinked);
    }

    @Override
    public boolean isEnforcedOn(String serverName) {
        return current.get().isEnforcedOn(serverName);
    }
}
