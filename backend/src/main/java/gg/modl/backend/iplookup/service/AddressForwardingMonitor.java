package gg.modl.backend.iplookup.service;

import gg.modl.address.AddressScope;
import gg.modl.backend.iplookup.settings.AddressForwardingNotice;
import gg.modl.backend.iplookup.settings.AddressForwardingNotice.Reason;
import gg.modl.backend.iplookup.settings.PlayerAddressSettingsStore;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AddressForwardingMonitor {
    private final PlayerAddressSettingsStore store;
    private final Clock clock;

    public void observeLogin(@Nullable String ip, @Nullable String serverName) {
        if (ip != null && AddressScope.ofLiteral(ip).map(scope -> !scope.isPublic()).orElse(false)) {
            record(Reason.NON_PUBLIC_ADDRESS, serverName);
        }
    }

    public void sharedAddressPaused() {
        record(Reason.SHARED_ADDRESS, null);
    }

    private void record(Reason reason, @Nullable String serverName) {
        if (store.notice().detected()) {
            return;
        }
        store.updateNotice(current -> current.detected()
            ? current
            : AddressForwardingNotice.detected(reason, serverName, clock.instant()));
    }
}
