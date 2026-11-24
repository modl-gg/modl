package gg.modl.backend.network;

import gg.modl.backend.serverkey.ServerKeyChangedEvent;
import gg.modl.backend.serverkey.ServerKeyRevokedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NetworkKeyEvents {
    private final NetworkHub hub;

    @EventListener
    public void onRevoked(ServerKeyRevokedEvent event) {
        hub.revoke(event.key());
    }

    @EventListener
    public void onChanged(ServerKeyChangedEvent event) {
        hub.refreshGrants(event.key());
    }
}
