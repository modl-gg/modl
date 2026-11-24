package gg.modl.minecraft.core.imports.support;

import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class SoleOwners {
    private final Map<String, String> ownerByAddress = new HashMap<>();
    private final Set<String> shared = new HashSet<>();

    public void used(@Nullable String address, @Nullable String account) {
        if (address == null || account == null || shared.contains(address)) return;
        String owner = ownerByAddress.putIfAbsent(address, account);
        if (owner != null && !owner.equals(account)) {
            ownerByAddress.remove(address);
            shared.add(address);
        }
    }

    public @Nullable String soleOwner(@Nullable String address) {
        return address == null ? null : ownerByAddress.get(address);
    }
}
