package gg.modl.backend.iplookup.model;

import org.jspecify.annotations.Nullable;
import java.util.Arrays;
import java.util.Optional;

public enum IpLookupProvider {
    PROXYCHECK("proxycheck", KeyUse.REQUIRED_UNCHECKED),
    VPNAPI("vpnapi", KeyUse.REQUIRED),
    IPHUB("iphub", KeyUse.REQUIRED),
    IPAPI_IS("ipapi_is", KeyUse.REQUIRED),
    PROXYCHECK_KEYLESS("proxycheck_nokey", KeyUse.NONE);

    private final String id;
    private final KeyUse keyUse;

    IpLookupProvider(String id, KeyUse keyUse) {
        this.id = id;
        this.keyUse = keyUse;
    }

    public String id() {
        return id;
    }

    public boolean needsKey() {
        return keyUse != KeyUse.NONE;
    }

    public boolean refusesUnknownKeys() {
        return keyUse == KeyUse.REQUIRED;
    }

    public static Optional<IpLookupProvider> ofId(@Nullable String id) {
        return Arrays.stream(values()).filter(provider -> provider.id.equals(id)).findFirst();
    }

    private enum KeyUse {
        REQUIRED,
        REQUIRED_UNCHECKED,
        NONE
    }
}
