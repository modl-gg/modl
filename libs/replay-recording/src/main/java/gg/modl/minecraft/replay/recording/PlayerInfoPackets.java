package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.adventure.AdventureSerializer;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

final class PlayerInfoPackets {
    static final int MAX_DISPLAY_NAME_BYTES = 1024;

    void register(PacketHandlers handlers) {
        handlers.onSend(PacketType.Play.Server.PLAYER_INFO, this::legacy);
        handlers.onSend(PacketType.Play.Server.PLAYER_INFO_UPDATE, this::update);
        handlers.onSend(PacketType.Play.Server.PLAYER_INFO_REMOVE, this::remove);
    }

    private void legacy(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerPlayerInfo packet = new WrapperPlayServerPlayerInfo(event);
        WrapperPlayServerPlayerInfo.Action action = packet.getAction();
        if (action == null) return;
        List<UUID> removed = new ArrayList<>();
        for (WrapperPlayServerPlayerInfo.PlayerData data : packet.getPlayerDataList()) {
            UserProfile profile = data.getUserProfile();
            if (profile == null || profile.getUUID() == null) continue;
            UUID uuid = profile.getUUID();
            switch (action) {
                case ADD_PLAYER: {
                    SkinTextures.Skin skin = SkinTextures.of(profile);
                    write(recording, time, new PlayerInfoPatch(uuid, name(profile), skin.getHash(), skin.isSlim(),
                            gameMode(data.getGameMode()), true, Math.max(0, data.getPing()), json(data.getDisplayName())));
                    break;
                }
                case UPDATE_GAME_MODE:
                    write(recording, time, new PlayerInfoPatch(uuid, null, "", false, gameMode(data.getGameMode()), null, null, null));
                    break;
                case UPDATE_LATENCY:
                    write(recording, time, new PlayerInfoPatch(uuid, null, "", false, null, null, Math.max(0, data.getPing()), null));
                    break;
                case UPDATE_DISPLAY_NAME:
                    write(recording, time, new PlayerInfoPatch(uuid, null, "", false, null, null, null, json(data.getDisplayName())));
                    break;
                case REMOVE_PLAYER:
                    removed.add(uuid);
                    break;
                default:
            }
        }
        if (!removed.isEmpty()) recording.append(RecordEncoder.playerInfoRemove(time, removed), time);
    }

    private void update(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerPlayerInfoUpdate packet = new WrapperPlayServerPlayerInfoUpdate(event);
        EnumSet<WrapperPlayServerPlayerInfoUpdate.Action> actions = packet.getActions();
        boolean add = actions.contains(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER);
        boolean mode = actions.contains(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE);
        boolean listed = actions.contains(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED);
        boolean latency = actions.contains(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY);
        boolean display = actions.contains(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME);
        for (WrapperPlayServerPlayerInfoUpdate.PlayerInfo info : packet.getEntries()) {
            UUID uuid = info.getProfileId();
            if (uuid == null) continue;
            UserProfile profile = info.getGameProfile();
            String name = add && profile != null ? name(profile) : null;
            SkinTextures.Skin skin = add ? SkinTextures.of(profile) : SkinTextures.NONE;
            write(recording, time, new PlayerInfoPatch(uuid, name, skin.getHash(), skin.isSlim(),
                    mode ? gameMode(info.getGameMode()) : null,
                    listed ? info.isListed() : null,
                    latency ? Math.max(0, info.getLatency()) : null,
                    display ? json(info.getDisplayName()) : null));
        }
    }

    private void remove(PacketSendEvent event, Recording recording, long time) {
        List<UUID> uuids = new WrapperPlayServerPlayerInfoRemove(event).getProfileIds();
        if (!uuids.isEmpty()) recording.append(RecordEncoder.playerInfoRemove(time, uuids), time);
    }

    private static void write(Recording recording, long time, PlayerInfoPatch patch) {
        recording.append(RecordEncoder.playerInfo(time, patch), time);
    }

    private static String name(UserProfile profile) {
        String name = profile.getName();
        return name == null ? "" : name;
    }

    private static @Nullable Integer gameMode(@Nullable GameMode mode) {
        return mode == null ? null : mode.getId() + 1;
    }

    private static String json(@Nullable Component component) {
        return component == null ? "" : displayName(AdventureSerializer.serializer().asJson(component));
    }

    static String displayName(String json) {
        if (json.length() > MAX_DISPLAY_NAME_BYTES) return "";
        return json.getBytes(StandardCharsets.UTF_8).length <= MAX_DISPLAY_NAME_BYTES ? json : "";
    }
}
