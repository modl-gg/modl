package gg.modl.backend.iplookup.settings;

import gg.modl.backend.iplookup.model.IpLookupProvider;
import org.jspecify.annotations.Nullable;
import java.util.List;

public record PlayerAddressSettingsView(
    @Nullable IpLookupProvider provider,
    List<IpLookupProvider> providersWithKeys,
    boolean lookupsActive,
    boolean choicePending,
    int sharedAccountLimit,
    AddressForwardingNotice notice
) {
}
