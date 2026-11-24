package gg.modl.backend.iplookup.model;

import org.jspecify.annotations.Nullable;

public record IpLookupAccess(IpLookupProvider provider, @Nullable String apiKey) {
    public IpLookupAccess {
        if (provider.needsKey() == (apiKey == null)) {
            throw new IllegalArgumentException(provider.needsKey()
                ? provider.id() + " needs an API key"
                : provider.id() + " is used without an API key");
        }
    }

    public static IpLookupAccess keyed(IpLookupProvider provider, String apiKey) {
        return new IpLookupAccess(provider, apiKey);
    }

    public static IpLookupAccess keyless(IpLookupProvider provider) {
        return new IpLookupAccess(provider, null);
    }

    public String requireKey() {
        if (apiKey == null) {
            throw new IllegalStateException(provider.id() + " is used without an API key");
        }
        return apiKey;
    }

    @Override
    public String toString() {
        return "IpLookupAccess[provider=" + provider + "]";
    }
}
