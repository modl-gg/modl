package gg.modl.minecraft.api;

import lombok.AllArgsConstructor;
import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

@Value @AllArgsConstructor
public class AbstractPlayer {
    UUID uuid;
    String username;
    @Nullable String ipAddress;
    boolean online;

    public AbstractPlayer(UUID uuid, String username, boolean online) {
        this(uuid, username, null, online);
    }

    public String getName() {
        return username;
    }
}
