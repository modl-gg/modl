package gg.modl.backend.update.check;

import gg.modl.backend.update.UpdateProperties;
import gg.modl.backend.update.state.InstanceState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CheckPolicy {
    private final UpdateProperties properties;
    private final ReleaseEnvelopes envelopes;

    public boolean checksAvailable() {
        return !properties.checksForcedOff() && envelopes.hasKeys();
    }

    public boolean automaticChecks(InstanceState state) {
        return checksAvailable() && (properties.checksManagedByConfig() || state.checksEnabled());
    }

    public boolean effectiveChecksEnabled(InstanceState state) {
        return properties.checksManagedByConfig() ? !properties.checksForcedOff() : state.checksEnabled();
    }

    public boolean settingsManagedByConfig() {
        return properties.checksManagedByConfig();
    }
}
