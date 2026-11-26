package gg.modl.minecraft.replay.recording;

import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

@Value
class PlayerInfoPatch {
    private static final int PROFILE = 1;
    private static final int GAME_MODE = 1 << 1;
    private static final int LISTED = 1 << 2;
    private static final int LATENCY = 1 << 3;
    private static final int DISPLAY_NAME = 1 << 4;

    UUID uuid;
    @Nullable String name;
    String skinHash;
    boolean slim;
    @Nullable Integer gameMode;
    @Nullable Boolean listed;
    @Nullable Integer latency;
    @Nullable String displayName;

    void write(RecordWriter out) {
        String currentName = name;
        Integer mode = gameMode;
        Boolean isListed = listed;
        Integer ping = latency;
        String display = displayName;
        int mask = (currentName != null ? PROFILE : 0) | (mode != null ? GAME_MODE : 0) | (isListed != null ? LISTED : 0)
                | (ping != null ? LATENCY : 0) | (display != null ? DISPLAY_NAME : 0);
        out.writeVarInt(mask).writeUuid(uuid);
        if (currentName != null) out.writeString(currentName).writeString(skinHash).writeBoolean(slim);
        if (mode != null) out.writeVarInt(mode);
        if (isListed != null) out.writeBoolean(isListed);
        if (ping != null) out.writeVarInt(ping);
        if (display != null) out.writeString(display);
    }

    static PlayerInfoPatch read(RecordReader in) {
        int mask = in.readVarInt();
        UUID uuid = in.readUuid();
        String name = null;
        String skinHash = "";
        boolean slim = false;
        if ((mask & PROFILE) != 0) {
            name = in.readString();
            skinHash = in.readString();
            slim = in.readBoolean();
        }
        Integer gameMode = (mask & GAME_MODE) != 0 ? in.readVarInt() : null;
        Boolean listed = (mask & LISTED) != 0 ? in.readBoolean() : null;
        Integer latency = (mask & LATENCY) != 0 ? in.readVarInt() : null;
        String displayName = (mask & DISPLAY_NAME) != 0 ? in.readString() : null;
        return new PlayerInfoPatch(uuid, name, skinHash, slim, gameMode, listed, latency, displayName);
    }
}
