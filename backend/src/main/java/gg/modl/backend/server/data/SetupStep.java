package gg.modl.backend.server.data;

import java.util.Arrays;
import java.util.Optional;

public enum SetupStep {
    ACCOUNT("account"),
    IMPORT("import"),
    IDENTITY("identity"),
    ADDRESS("address"),
    EMAIL("email"),
    SERVERS("servers"),
    MINECRAFT_ACCOUNT("minecraft-account"),
    STAFF("staff"),
    PLAYER_ADDRESSES("player-addresses"),
    AI("ai"),
    REPLAYS("replays"),
    CONTENT("content");

    private final String pathName;

    SetupStep(String pathName) {
        this.pathName = pathName;
    }

    public String pathName() {
        return pathName;
    }

    public static Optional<SetupStep> fromPathName(String pathName) {
        return Arrays.stream(values()).filter(step -> step.pathName.equals(pathName)).findFirst();
    }
}
