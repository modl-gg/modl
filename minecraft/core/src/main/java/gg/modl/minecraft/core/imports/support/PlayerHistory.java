package gg.modl.minecraft.core.imports.support;

import gg.modl.minecraft.core.imports.source.SourcePlayer;
import gg.modl.minecraft.core.imports.source.SourcePunishment;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PlayerHistory {
    private static final int MAX_LOGINS_PER_ADDRESS = 1000;

    private final Map<String, Long> firstSeenByName = new LinkedHashMap<>();
    private final Map<String, Set<Long>> loginsByAddress = new LinkedHashMap<>();
    private final List<SourcePunishment> punishments = new ArrayList<>();

    public void seen(@Nullable String name, @Nullable String address, long at) {
        if (name != null && !name.trim().isEmpty()) {
            firstSeenByName.merge(name.trim(), at, Math::min);
        }
        if (address != null && !address.trim().isEmpty()) {
            Set<Long> logins = loginsByAddress.computeIfAbsent(address.trim(), unseen -> new LinkedHashSet<>());
            if (logins.size() < MAX_LOGINS_PER_ADDRESS) logins.add(at);
        }
    }

    public void punished(SourcePunishment punishment) {
        punishments.add(punishment);
    }

    public SourcePlayer toPlayer(String uuid) {
        return toPlayer(uuid, punishments);
    }

    public SourcePlayer toPlayer(String uuid, List<SourcePunishment> punished) {
        List<SourcePlayer.Username> usernames = new ArrayList<>();
        firstSeenByName.forEach((name, at) -> usernames.add(new SourcePlayer.Username(name, at)));
        usernames.sort(Comparator.comparingLong(SourcePlayer.Username::getSeenAt));
        List<SourcePlayer.Address> addresses = new ArrayList<>();
        loginsByAddress.forEach((ip, logins) ->
                addresses.add(new SourcePlayer.Address(ip, Collections.unmodifiableSet(logins))));
        List<SourcePunishment> sorted = new ArrayList<>(punished);
        sorted.sort(Comparator.comparingLong(SourcePunishment::getIssuedAt));
        return new SourcePlayer(uuid, usernames, addresses, sorted);
    }
}
