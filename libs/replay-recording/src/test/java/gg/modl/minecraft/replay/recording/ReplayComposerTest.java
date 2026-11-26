package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.netty.buffer.UnpooledByteBufAllocationHelper;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.LightData;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.dimension.DimensionTypes;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import gg.modl.minecraft.replay.format.ReplayFileReader;
import gg.modl.minecraft.replay.format.ReplayLimits;
import gg.modl.proto.modl.v1.ReplayBlockState;
import gg.modl.proto.modl.v1.ReplayEvent;
import gg.modl.proto.modl.v1.ReplayFrame;
import gg.modl.proto.modl.v1.ReplayHeader;
import gg.modl.proto.modl.v1.ReplayKeyframe;
import gg.modl.proto.modl.v1.ReplayWorldSection;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayComposerTest {
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000007");

    @Test
    void composesLegacyChunksAndEventsWithNames() throws IOException {
        TestPacketEvents.install(ServerVersion.V_1_8_8);
        Parsed replay = compose(ServerVersion.V_1_8_8, recording -> {
            recording.enterWorld(WorldPackets.OVERWORLD, "world", 0, 256, true, null, 42);
            byte[] chunk = legacyChunk(53 << 4 | 1);
            recording.storeChunk(0, 0, RawPacket.compress(RawPacket.LEGACY_CHUNK, chunk, new DeflaterPool(1)), -1, 0, recording.now());
        });

        assertEquals(5, replay.header.getFormatVersion());
        assertEquals(0, replay.header.getDimension().getMinY());
        assertEquals(256, replay.header.getDimension().getHeight());
        assertEquals("minecraft:oak_stairs", replay.stateAt(1, 2, 3).getName());
        assertEquals("west", property(replay.stateAt(1, 2, 3), "facing"));
        assertEquals("minecraft:air", replay.stateAt(0, 0, 0).getName());
        assertTrue(replay.header.getLightRecorded());
        assertCommonContent(replay);
    }

    @Test
    void composesModernChunksWithNegativeMinY() throws IOException {
        TestPacketEvents.install(ServerVersion.V_1_21_11);
        Parsed replay = compose(ServerVersion.V_1_21_11, recording -> {
            recording.enterWorld(WorldPackets.OVERWORLD, "minecraft:overworld", -64, 384, true, DimensionTypes.OVERWORLD, 42);
            byte[] chunk = modernChunk(ClientVersion.V_1_21_11, -64, 384);
            recording.storeChunk(0, 0, RawPacket.compress(RawPacket.CHUNK_DATA, chunk, new DeflaterPool(1)), -1, 0, recording.now());
        });

        assertEquals(-64, replay.header.getDimension().getMinY());
        assertEquals("minecraft:oak_stairs", replay.stateAt(1, -60, 3).getName());
        assertEquals("minecraft:stone", replay.stateAt(5, 70, 5).getName());
        assertCommonContent(replay);
    }

    @Test
    void aSeededStartListsTheTargetItself() throws IOException {
        TestPacketEvents.install(ServerVersion.V_1_21_11);
        Parsed replay = compose(ServerVersion.V_1_21_11, recording -> new RecordingSeed(recording,
                new TranslationContext(ServerVersion.V_1_21_11, new NameTable())).targetProfile(null, 1));

        assertTrue(replay.events.stream().anyMatch(event -> event.hasPlayerInfo()
                && event.getPlayerInfo().getPlayer().getName().equals("Target")
                && event.getPlayerInfo().getPlayer().getUuid().equals(TARGET.toString()))
                || replay.keyframes.stream().anyMatch(keyframe -> keyframe.getPlayersList().stream()
                .anyMatch(player -> player.getName().equals("Target"))));
    }

    @Test
    void seedingAVisiblePlayerRecordsItsProfileAndItsEntity() throws IOException {
        TestPacketEvents.install(ServerVersion.V_1_21_11);
        UUID alex = UUID.fromString("00000000-0000-0000-0000-000000000077");
        String textures = Base64.getEncoder().encodeToString(
                "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/beef01\"}}}".getBytes(StandardCharsets.UTF_8));
        UserProfile profile = new UserProfile(alex, "Alex", Collections.singletonList(new TextureProperty("textures", textures, null)));
        Parsed replay = compose(ServerVersion.V_1_21_11, recording -> {
            recording.enterWorld(WorldPackets.OVERWORLD, "minecraft:overworld", -64, 384, true, DimensionTypes.OVERWORLD, 42);
            new RecordingSeed(recording, new TranslationContext(ServerVersion.V_1_21_11, new NameTable()))
                    .player(77, alex, "Alex", profile, 1, 2.5, 65, 2.5, 90F, 0F, Collections.emptyList(), Collections.emptyMap());
        });

        assertTrue(replay.events.stream().anyMatch(event -> event.hasPlayerInfo()
                && event.getPlayerInfo().getPlayer().getName().equals("Alex")
                && event.getPlayerInfo().getPlayer().getSkinHash().equals("beef01")));
        assertTrue(replay.events.stream().anyMatch(event -> event.hasEntitySpawn()
                && event.getEntitySpawn().getEntity().getEntityId() == 77
                && event.getEntitySpawn().getEntity().getUuid().equals(alex.toString())
                && replay.strings.get(event.getEntitySpawn().getEntity().getType()).equals("minecraft:player")));
    }

    @Test
    void framesStayUnderTheFrameLimitWithThousandsOfTabEntries() throws IOException {
        TestPacketEvents.install(ServerVersion.V_1_21_11);
        UUID nearby = UUID.fromString("00000000-0000-0000-0000-000000000099");
        StringBuilder longest = new StringBuilder("{\"text\":\"");
        while (longest.length() < PlayerInfoPackets.MAX_DISPLAY_NAME_BYTES - 2) longest.append('x');
        String displayName = PlayerInfoPackets.displayName(longest.append("\"}").toString());
        assertEquals(PlayerInfoPackets.MAX_DISPLAY_NAME_BYTES, displayName.length());
        Parsed replay = compose(ServerVersion.V_1_21_11, recording -> {
            recording.enterWorld(WorldPackets.OVERWORLD, "minecraft:overworld", -64, 384, true, DimensionTypes.OVERWORLD, 42);
            TranslationContext context = new TranslationContext(ServerVersion.V_1_21_11, new NameTable());
            long time = recording.now();
            for (int i = 0; i < 6000; i++) {
                UUID uuid = i == 0 ? nearby : new UUID(1L, i);
                recording.append(RecordEncoder.playerInfo(time, new PlayerInfoPatch(uuid, "player" + i, "abc", false, 1, true, 50,
                        displayName)), time);
            }
            recording.append(RecordEncoder.entitySpawn(time, 500, context.getPlayerType(), nearby, Fixed.of(2), Fixed.of(65), Fixed.of(2),
                    0F, 0F, 0F, 0), time);
            recording.append(RecordEncoder.targetMove(time + 25_000, Records.MOVE_POSITION, Fixed.of(3), Fixed.of(65), Fixed.of(3),
                    0F, 0F, true), time + 25_000);
        });

        assertTrue(replay.largestFrameBytes < ReplayLimits.MAX_FRAME_BYTES);
        assertTrue(replay.keyframes.size() > 1);
        ReplayKeyframe last = replay.keyframes.get(replay.keyframes.size() - 1);
        assertTrue(last.getPlayersList().stream().anyMatch(player -> player.getUuid().equals(nearby.toString())));
        assertTrue(last.getPlayersList().size() <= 2);
    }

    @Test
    void aPlayerWhoWalksInAfterTheKeyframeIsAnnouncedBeforeTheirSpawn() throws IOException {
        TestPacketEvents.install(ServerVersion.V_1_21_11);
        UUID walker = UUID.fromString("00000000-0000-0000-0000-000000000055");
        Parsed replay = compose(ServerVersion.V_1_21_11, recording -> {
            recording.enterWorld(WorldPackets.OVERWORLD, "minecraft:overworld", -64, 384, true, DimensionTypes.OVERWORLD, 42);
            TranslationContext context = new TranslationContext(ServerVersion.V_1_21_11, new NameTable());
            recording.append(RecordEncoder.playerInfo(1_000, new PlayerInfoPatch(walker, "Walker", "cafe01", false, 1, true, 20, "")), 1_000);
            recording.append(RecordEncoder.targetMove(150_000, Records.MOVE_POSITION, Fixed.of(1), Fixed.of(65), Fixed.of(1),
                    0F, 0F, true), 150_000);
            recording.append(RecordEncoder.entitySpawn(162_000, 600, context.getPlayerType(), walker, Fixed.of(4), Fixed.of(65),
                    Fixed.of(4), 0F, 0F, 0F, 0), 162_000);
        }, 200_000);

        int announced = -1;
        int spawned = -1;
        for (int i = 0; i < replay.events.size(); i++) {
            ReplayEvent event = replay.events.get(i);
            if (announced < 0 && event.hasPlayerInfo() && event.getPlayerInfo().getPlayer().getUuid().equals(walker.toString())) {
                announced = i;
                assertEquals("cafe01", event.getPlayerInfo().getPlayer().getSkinHash());
            }
            if (event.hasEntitySpawn() && event.getEntitySpawn().getEntity().getEntityId() == 600) spawned = i;
        }
        assertTrue(announced >= 0);
        assertTrue(spawned > announced);
        assertTrue(replay.keyframes.get(0).getPlayersList().stream().noneMatch(player -> player.getUuid().equals(walker.toString())));
    }

    @Test
    void anOversizedDisplayNameIsDroppedAtIngest() {
        StringBuilder name = new StringBuilder();
        while (name.length() <= PlayerInfoPackets.MAX_DISPLAY_NAME_BYTES) name.append('x');

        assertEquals("", PlayerInfoPackets.displayName(name.toString()));
        assertEquals("", PlayerInfoPackets.displayName(name.substring(0, 400).replace('x', '\u00e9') + name.substring(400, 800)));
        assertEquals("{\"text\":\"a\"}", PlayerInfoPackets.displayName("{\"text\":\"a\"}"));
    }

    private static void assertCommonContent(Parsed replay) {
        ReplayKeyframe first = replay.keyframes.get(0);
        assertEquals(0, first.getTimeMs());
        assertTrue(first.getEntitiesList().stream().anyMatch(entity -> entity.getEntityId() == 42));
        assertTrue(replay.events.stream().anyMatch(event -> event.hasEntitySpawn()
                && replay.strings.get(event.getEntitySpawn().getEntity().getType()).equals("minecraft:zombie")));
        assertTrue(replay.events.stream().anyMatch(event -> event.hasPlayerInfo()
                && event.getPlayerInfo().getPlayer().getName().equals("Other")
                && event.getPlayerInfo().getPlayer().getSkinHash().equals("abc123")));
        assertTrue(replay.events.stream().anyMatch(event -> event.hasChat() && event.getChat().getComponentJson().contains("hello")));
        assertTrue(replay.events.stream().anyMatch(event -> event.hasBlockChange()
                && replay.palette.get(event.getBlockChange().getState()).getName().equals("minecraft:air")));
        assertTrue(replay.events.stream().anyMatch(event -> event.hasEntityMove() && event.getEntityMove().getEntityId() == 42));
        assertTrue(replay.ended);
    }

    private interface Setup {
        void prepare(Recording recording);
    }

    private static Parsed compose(ServerVersion version, Setup setup) throws IOException {
        return compose(version, setup, 10);
    }

    private static Parsed compose(ServerVersion version, Setup setup, long captureAfterMs) throws IOException {
        NameTable names = new NameTable();
        TranslationContext context = new TranslationContext(version, names);
        MemoryBudget budget = new MemoryBudget(64L * 1024 * 1024);
        Recording recording = new Recording(TARGET, "Target", RecordingCause.AUTO_RECORD, context.getPlayerType(), budget, Runnable::run,
                () -> RadiusChunks.of(64), e -> { throw e; });
        setup.prepare(recording);
        long time = recording.now();
        recording.append(RecordEncoder.targetMove(time, Records.MOVE_POSITION | Records.MOVE_ROTATION, Fixed.of(0.5), Fixed.of(65), Fixed.of(0.5), 90F, 0F, true), time);
        recording.append(RecordEncoder.playerInfo(time, new PlayerInfoPatch(OTHER, "Other", "abc123", true, 1, true, 20, "")), time);
        recording.append(RecordEncoder.entitySpawn(time, 7, context.typeName(EntityTypes.ZOMBIE), null, Fixed.of(3), Fixed.of(65), Fixed.of(3), 0F, 0F, 0F, 0), time);
        recording.append(RecordEncoder.targetMove(time + 1, Records.MOVE_POSITION, Fixed.of(1.5), Fixed.of(65), Fixed.of(0.5), 0F, 0F, true), time + 1);
        recording.append(RecordEncoder.chat(time + 2, ChatPackets.SYSTEM, "{\"text\":\"hello\"}", null), time + 2);
        recording.append(RecordEncoder.blockChange(time + 3, 1, version.isOlderThan(ServerVersion.V_1_18) ? 2 : -60, 3, 0), time + 3);
        ReplayComposer composer = new ReplayComposer(RecorderSettings.builder().windowSeconds(60).radiusBlocks(64)
                .memoryBudgetBytes(1L).minecraftVersion(version.getReleaseName()).dataVersion(0).serverSoftware("test")
                .recorderVersion("test").build(), version, names, context.typeName(EntityTypes.FALLING_BLOCK), Logger.getAnonymousLogger());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        composer.compose(new ComposeRequest("replay", "test", recording.snapshot(recording.now() + captureAfterMs), 60_000L, 64), out);
        return Parsed.of(out.toByteArray());
    }

    private static String property(ReplayBlockState state, String name) {
        return state.getPropertiesList().stream().filter(p -> p.getName().equals(name)).findFirst()
                .map(p -> p.getValue()).orElse("");
    }

    private static byte[] legacyChunk(int stairsState) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        byte[] blocks = new byte[8192];
        int index = DecodedSection.index(1, 2, 3);
        blocks[index * 2] = (byte) (stairsState & 0xFF);
        blocks[index * 2 + 1] = (byte) (stairsState >> 8);
        data.write(blocks, 0, blocks.length);
        data.write(new byte[2048], 0, 2048);
        byte[] sky = new byte[2048];
        Arrays.fill(sky, (byte) 0xFF);
        data.write(sky, 0, sky.length);
        data.write(new byte[256], 0, 256);
        byte[] body = data.toByteArray();
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        writeInt(packet, 0);
        writeInt(packet, 0);
        packet.write(1);
        packet.write(0);
        packet.write(1);
        int size = body.length;
        while ((size & ~0x7F) != 0) {
            packet.write((size & 0x7F) | 0x80);
            size >>>= 7;
        }
        packet.write(size);
        packet.write(body, 0, body.length);
        return packet.toByteArray();
    }

    private static byte[] modernChunk(ClientVersion version, int minY, int height) {
        Chunk_v1_18[] sections = new Chunk_v1_18[height >> 4];
        for (int i = 0; i < sections.length; i++) sections[i] = new Chunk_v1_18(version);
        int stairs = WrappedBlockState.getDefaultState(version, StateTypes.OAK_STAIRS).getGlobalId();
        int stone = WrappedBlockState.getDefaultState(version, StateTypes.STONE).getGlobalId();
        sections[(-60 - minY) >> 4].set(version, 1, (-60 - minY) & 15, 3, stairs);
        sections[(70 - minY) >> 4].set(version, 5, (70 - minY) & 15, 5, stone);
        sections[(-60 - minY) >> 4].setBlockCount(1);
        sections[(70 - minY) >> 4].setBlockCount(1);
        Column column = new Column(0, 0, true, (BaseChunk[]) sections, new TileEntity[0], new HashMap<>());
        LightData light = new LightData(false, new BitSet(), new BitSet(), new BitSet(), new BitSet(), 0, 0, new byte[0][], new byte[0][]);
        WrapperPlayServerChunkData wrapper = new WrapperPlayServerChunkData(column, light);
        Object buffer = UnpooledByteBufAllocationHelper.buffer();
        wrapper.buffer = buffer;
        wrapper.write();
        byte[] raw = new byte[ByteBufHelper.readableBytes(buffer)];
        ByteBufHelper.getBytes(buffer, ByteBufHelper.readerIndex(buffer), raw);
        ByteBufHelper.release(buffer);
        return raw;
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    private static final class Parsed {
        private ReplayHeader header = ReplayHeader.getDefaultInstance();
        private final List<String> strings = new ArrayList<>();
        private final List<ReplayBlockState> palette = new ArrayList<>();
        private final Map<String, ReplayWorldSection> sections = new HashMap<>();
        private final List<ReplayKeyframe> keyframes = new ArrayList<>();
        private final List<ReplayEvent> events = new ArrayList<>();
        private boolean ended;
        private int largestFrameBytes;

        static Parsed of(byte[] file) throws IOException {
            Parsed parsed = new Parsed();
            try (ReplayFileReader reader = ReplayFileReader.open(new ByteArrayInputStream(file), ReplayLimits.MAX_UNCOMPRESSED_BYTES)) {
                ReplayFrame frame;
                while ((frame = reader.next()) != null) {
                    parsed.largestFrameBytes = Math.max(parsed.largestFrameBytes, frame.getSerializedSize());
                    switch (frame.getFrameCase()) {
                        case HEADER -> parsed.header = frame.getHeader();
                        case STRINGS -> parsed.strings.addAll(frame.getStrings().getValuesList());
                        case BLOCK_PALETTE -> parsed.palette.addAll(frame.getBlockPalette().getStatesList());
                        case SECTION -> {
                            ReplayWorldSection section = frame.getSection();
                            parsed.sections.put(section.getX() + "," + section.getY() + "," + section.getZ(), section);
                        }
                        case KEYFRAME -> parsed.keyframes.add(frame.getKeyframe());
                        case EVENTS -> parsed.events.addAll(frame.getEvents().getEventsList());
                        case END -> parsed.ended = true;
                        default -> {
                        }
                    }
                }
            }
            return parsed;
        }

        ReplayBlockState stateAt(int x, int y, int z) {
            ReplayWorldSection section = sections.get((x >> 4) + "," + (y >> 4) + "," + (z >> 4));
            if (section == null) return palette.get(0);
            return palette.get(section.getStates(DecodedSection.index(x & 15, y & 15, z & 15)));
        }
    }
}
