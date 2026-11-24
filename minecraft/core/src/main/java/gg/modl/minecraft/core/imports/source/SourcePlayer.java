package gg.modl.minecraft.core.imports.source;

import lombok.Value;

import java.util.List;
import java.util.Set;

@Value
public class SourcePlayer {
    String uuid;
    List<Username> usernames;
    List<Address> addresses;
    List<SourcePunishment> punishments;

    public boolean hasHistory() {
        return !usernames.isEmpty() || !addresses.isEmpty();
    }

    @Value
    public static class Username {
        String name;
        long seenAt;
    }

    @Value
    public static class Address {
        String ip;
        Set<Long> logins;
    }
}
