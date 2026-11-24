package gg.modl.backend.iplookup.settings;

import gg.modl.backend.iplookup.model.IpLookupAccess;
import gg.modl.backend.iplookup.model.IpLookupProvider;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public record PlayerAddressSettings(
    @Nullable String provider,
    Map<String, String> keys,
    @Nullable Integer sharedAccountLimit,
    @Nullable Long choiceMadeAt
) {
    public static final int DEFAULT_SHARED_ACCOUNT_LIMIT = 10;
    public static final int MIN_SHARED_ACCOUNT_LIMIT = 2;
    public static final int MAX_SHARED_ACCOUNT_LIMIT = 1000;
    public static final PlayerAddressSettings EMPTY = new PlayerAddressSettings(null, Map.of(), null, null);

    public PlayerAddressSettings {
        keys = keys == null ? Map.of() : Map.copyOf(keys);
    }

    public static boolean validSharedAccountLimit(int limit) {
        return limit >= MIN_SHARED_ACCOUNT_LIMIT && limit <= MAX_SHARED_ACCOUNT_LIMIT;
    }

    public Optional<IpLookupProvider> selectedProvider() {
        return IpLookupProvider.ofId(provider);
    }

    public Optional<String> key(IpLookupProvider lookupProvider) {
        return Optional.ofNullable(keys().get(lookupProvider.id())).filter(key -> !key.isBlank());
    }

    public boolean hasKey(IpLookupProvider lookupProvider) {
        return key(lookupProvider).isPresent();
    }

    public boolean canUse(IpLookupProvider lookupProvider) {
        return !lookupProvider.needsKey() || hasKey(lookupProvider);
    }

    public Optional<IpLookupAccess> activeLookup() {
        return selectedProvider().flatMap(this::access);
    }

    public Optional<IpLookupAccess> access(IpLookupProvider lookupProvider) {
        if (!lookupProvider.needsKey()) {
            return Optional.of(IpLookupAccess.keyless(lookupProvider));
        }
        return key(lookupProvider).map(key -> IpLookupAccess.keyed(lookupProvider, key));
    }

    public boolean choicePending() {
        return choiceMadeAt == null && selectedProvider().isEmpty();
    }

    public int effectiveSharedAccountLimit() {
        return sharedAccountLimit != null && validSharedAccountLimit(sharedAccountLimit)
            ? sharedAccountLimit
            : DEFAULT_SHARED_ACCOUNT_LIMIT;
    }

    public PlayerAddressSettings withProvider(@Nullable IpLookupProvider selected) {
        return new PlayerAddressSettings(selected == null ? null : selected.id(), keys, sharedAccountLimit, choiceMadeAt);
    }

    public PlayerAddressSettings withChoiceMade(Instant at) {
        return new PlayerAddressSettings(provider, keys, sharedAccountLimit, at.toEpochMilli());
    }

    public PlayerAddressSettings withKey(IpLookupProvider lookupProvider, String key) {
        Map<String, String> next = new LinkedHashMap<>(keys());
        next.put(lookupProvider.id(), key);
        return new PlayerAddressSettings(provider, next, sharedAccountLimit, choiceMadeAt);
    }

    public PlayerAddressSettings withoutKey(IpLookupProvider lookupProvider) {
        Map<String, String> next = new LinkedHashMap<>(keys());
        next.remove(lookupProvider.id());
        String stillSelected = lookupProvider.id().equals(provider) ? null : provider;
        return new PlayerAddressSettings(stillSelected, next, sharedAccountLimit, choiceMadeAt);
    }

    public PlayerAddressSettings withSharedAccountLimit(int limit) {
        return new PlayerAddressSettings(provider, keys, limit, choiceMadeAt);
    }

    @Override
    public String toString() {
        return "PlayerAddressSettings[provider=" + provider + ", keys=" + keys().keySet()
            + ", sharedAccountLimit=" + sharedAccountLimit + ", choiceMadeAt=" + choiceMadeAt + "]";
    }
}
