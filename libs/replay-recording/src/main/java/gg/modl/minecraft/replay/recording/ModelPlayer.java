package gg.modl.minecraft.replay.recording;

import lombok.Getter;

import java.util.UUID;

@Getter
final class ModelPlayer {
    private final UUID uuid;
    private String name = "";
    private String skinHash = "";
    private boolean slim;
    private int gameMode;
    private boolean listed = true;
    private int latency;
    private String displayName = "";

    ModelPlayer(UUID uuid) {
        this.uuid = uuid;
    }

    ModelPlayer copy() {
        ModelPlayer copy = new ModelPlayer(uuid);
        copy.name = name;
        copy.skinHash = skinHash;
        copy.slim = slim;
        copy.gameMode = gameMode;
        copy.listed = listed;
        copy.latency = latency;
        copy.displayName = displayName;
        return copy;
    }

    void apply(PlayerInfoPatch patch) {
        String newName = patch.getName();
        if (newName != null) {
            name = newName;
            skinHash = patch.getSkinHash();
            slim = patch.isSlim();
        }
        Integer mode = patch.getGameMode();
        if (mode != null) gameMode = mode;
        Boolean isListed = patch.getListed();
        if (isListed != null) listed = isListed;
        Integer ping = patch.getLatency();
        if (ping != null) latency = ping;
        String display = patch.getDisplayName();
        if (display != null) displayName = display;
    }
}
