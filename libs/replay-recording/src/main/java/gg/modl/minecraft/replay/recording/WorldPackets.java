package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.protocol.world.clock.ClockNetworkState;
import com.github.retrooper.packetevents.protocol.world.clock.WorldClock;
import com.github.retrooper.packetevents.protocol.world.dimension.DimensionType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChangeGameState;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerJoinGame;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRespawn;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTimeUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;
import org.jspecify.annotations.Nullable;

import java.util.Map;

final class WorldPackets {
    static final String OVERWORLD = "minecraft:overworld";
    static final String NETHER = "minecraft:the_nether";
    static final String END = "minecraft:the_end";

    private static final long TIME_INTERVAL_MS = 5_000L;
    private static final long TIME_JUMP_TICKS = 1_200L;
    private static final int LEGACY_HEIGHT = 256;

    private final ChunkIngest chunks;
    private final boolean legacyChunks;
    private final boolean worldHeights;
    private final boolean worldClocks;

    WorldPackets(TranslationContext context, ChunkIngest chunks) {
        this.chunks = chunks;
        this.legacyChunks = context.getServerVersion().isOlderThan(ServerVersion.V_1_9);
        this.worldHeights = context.getServerVersion().isNewerThanOrEquals(ServerVersion.V_1_18);
        this.worldClocks = context.getServerVersion().isNewerThanOrEquals(ServerVersion.V_26_1);
    }

    void register(PacketHandlers handlers) {
        handlers.onSend(PacketType.Play.Server.BLOCK_CHANGE, this::blockChange);
        handlers.onSend(PacketType.Play.Server.MULTI_BLOCK_CHANGE, this::multiBlockChange);
        handlers.onSend(PacketType.Play.Server.CHUNK_DATA, (event, recording, time) ->
                chunks.chunk(event, recording, time, legacyChunks ? RawPacket.LEGACY_CHUNK : RawPacket.CHUNK_DATA));
        handlers.onSend(PacketType.Play.Server.MAP_CHUNK_BULK, (event, recording, time) ->
                chunks.chunk(event, recording, time, RawPacket.LEGACY_BULK));
        handlers.onSend(PacketType.Play.Server.UPDATE_LIGHT, (event, recording, time) -> chunks.light(event, recording, time));
        handlers.onSend(PacketType.Play.Server.UNLOAD_CHUNK, this::unload);
        handlers.onSend(PacketType.Play.Server.JOIN_GAME, this::join);
        handlers.onSend(PacketType.Play.Server.RESPAWN, this::respawn);
        handlers.onSend(PacketType.Play.Server.TIME_UPDATE, this::time);
        handlers.onSend(PacketType.Play.Server.CHANGE_GAME_STATE, this::gameState);
        handlers.onSend(PacketType.Play.Server.PLAYER_POSITION_AND_LOOK, this::teleport);
    }

    private void blockChange(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerBlockChange packet = new WrapperPlayServerBlockChange(event);
        Vector3i position = packet.getBlockPosition();
        recording.append(RecordEncoder.blockChange(time, position.getX(), position.getY(), position.getZ(), packet.getBlockId()), time);
    }

    private void multiBlockChange(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerMultiBlockChange packet = new WrapperPlayServerMultiBlockChange(event);
        for (WrapperPlayServerMultiBlockChange.EncodedBlock block : packet.getBlocks()) {
            recording.append(RecordEncoder.blockChange(time, block.getX(), block.getY(), block.getZ(), block.getBlockId()), time);
        }
    }

    private void unload(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerUnloadChunk packet = new WrapperPlayServerUnloadChunk(event);
        chunks.unload(recording, packet.getChunkX(), packet.getChunkZ(), time);
    }

    private void join(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerJoinGame packet = new WrapperPlayServerJoinGame(event);
        recording.hardcore(packet.isHardcore());
        enter(recording, event.getUser(), packet.getWorldName(), packet.getDimensionType(), packet.getEntityId());
        gameMode(recording, time, packet.getGameMode());
    }

    private void respawn(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerRespawn packet = new WrapperPlayServerRespawn(event);
        enter(recording, event.getUser(), packet.getWorldName().orElse(null), packet.getDimensionType(), recording.targetEntityId());
        gameMode(recording, time, packet.getGameMode());
    }

    private void enter(Recording recording, @Nullable User user, @Nullable String worldName,
                       @Nullable DimensionType packetType, int entityId) {
        DimensionType dimensionType = packetType != null ? packetType : user == null ? null : user.getDimensionType();
        String key = worldName != null && worldName.indexOf(':') > 0 ? worldName : legacyKey(dimensionType);
        int minY = 0;
        int height = LEGACY_HEIGHT;
        if (worldHeights) {
            if (dimensionType != null) {
                minY = dimensionType.getMinY();
                height = dimensionType.getHeight();
            } else if (user != null) {
                minY = user.getMinWorldHeight();
                height = user.getTotalWorldHeight();
            }
        }
        boolean skyLight = dimensionType != null ? dimensionType.hasSkyLight() : !NETHER.equals(key) && !END.equals(key);
        recording.enterWorld(key, worldName == null ? key : worldName, minY, height, skyLight, dimensionType, entityId);
    }

    private static String legacyKey(@Nullable DimensionType type) {
        if (type == null) return OVERWORLD;
        if (type.hasCeiling()) return NETHER;
        return type.hasSkyLight() ? OVERWORLD : END;
    }

    private static void gameMode(Recording recording, long time, @Nullable GameMode mode) {
        if (mode != null) recording.append(RecordEncoder.targetGameMode(time, mode.getId() + 1), time);
    }

    private void time(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerTimeUpdate packet = new WrapperPlayServerTimeUpdate(event);
        Long reported = worldClocks ? overworldClock(packet) : Long.valueOf(packet.getTimeOfDay());
        if (reported == null) return;
        long timeOfDay = Math.abs(reported);
        if (!recording.shouldRecordTime(time, timeOfDay, TIME_INTERVAL_MS, TIME_JUMP_TICKS)) return;
        recording.append(RecordEncoder.worldTime(time, timeOfDay), time);
    }

    private static @Nullable Long overworldClock(WrapperPlayServerTimeUpdate packet) {
        Map<WorldClock, ClockNetworkState> clocks = packet.getClockUpdates();
        if (clocks == null) return null;
        for (Map.Entry<WorldClock, ClockNetworkState> clock : clocks.entrySet()) {
            if (OVERWORLD.equals(clock.getKey().getName().toString())) return clock.getValue().getTotalTicks();
        }
        return null;
    }

    private void gameState(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerChangeGameState packet = new WrapperPlayServerChangeGameState(event);
        WrapperPlayServerChangeGameState.Reason reason = packet.getReason();
        if (reason == null) return;
        switch (reason) {
            case BEGIN_RAINING:
                weather(recording, time, 1F, recording.thunder());
                return;
            case END_RAINING:
                weather(recording, time, 0F, 0F);
                return;
            case RAIN_LEVEL_CHANGE:
                weather(recording, time, packet.getValue(), recording.thunder());
                return;
            case THUNDER_LEVEL_CHANGE:
                weather(recording, time, recording.rain(), packet.getValue());
                return;
            case CHANGE_GAME_MODE:
                recording.append(RecordEncoder.targetGameMode(time, Math.round(packet.getValue()) + 1), time);
                return;
            default:
        }
    }

    private static void weather(Recording recording, long time, float newRain, float newThunder) {
        recording.weather(newRain, newThunder);
        recording.append(RecordEncoder.weather(time, newRain, newThunder), time);
    }

    private void teleport(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerPlayerPositionAndLook packet = new WrapperPlayServerPlayerPositionAndLook(event);
        RelativeFlag flags = packet.getRelativeFlags();
        int mask = Records.MOVE_POSITION | Records.MOVE_ROTATION;
        if (flags != null) {
            if (flags.has(RelativeFlag.X)) mask |= Records.MOVE_RELATIVE_X;
            if (flags.has(RelativeFlag.Y)) mask |= Records.MOVE_RELATIVE_Y;
            if (flags.has(RelativeFlag.Z)) mask |= Records.MOVE_RELATIVE_Z;
            if (flags.has(RelativeFlag.YAW)) mask |= Records.MOVE_RELATIVE_YAW;
            if (flags.has(RelativeFlag.PITCH)) mask |= Records.MOVE_RELATIVE_PITCH;
        }
        recording.append(RecordEncoder.targetMove(time, mask, Fixed.of(packet.getX()), Fixed.of(packet.getY()), Fixed.of(packet.getZ()),
                packet.getYaw(), packet.getPitch(), false), time);
    }
}
