package gg.modl.minecraft.replay.recording;

import gg.modl.proto.modl.v1.ReplayAnimation;
import gg.modl.proto.modl.v1.ReplayBlockBreakProgress;
import gg.modl.proto.modl.v1.ReplayBlockChange;
import gg.modl.proto.modl.v1.ReplayChat;
import gg.modl.proto.modl.v1.ReplayChatKind;
import gg.modl.proto.modl.v1.ReplayDimensionChange;
import gg.modl.proto.modl.v1.ReplayEntityAnimation;
import gg.modl.proto.modl.v1.ReplayEntityDeath;
import gg.modl.proto.modl.v1.ReplayEntityEffectRemove;
import gg.modl.proto.modl.v1.ReplayEntityEffectUpdate;
import gg.modl.proto.modl.v1.ReplayEntityEquipmentUpdate;
import gg.modl.proto.modl.v1.ReplayEntityHurt;
import gg.modl.proto.modl.v1.ReplayEntityMetadataUpdate;
import gg.modl.proto.modl.v1.ReplayEntityMove;
import gg.modl.proto.modl.v1.ReplayEntityPassengers;
import gg.modl.proto.modl.v1.ReplayEntityRemove;
import gg.modl.proto.modl.v1.ReplayEntitySpawn;
import gg.modl.proto.modl.v1.ReplayEntityTeleport;
import gg.modl.proto.modl.v1.ReplayEvent;
import gg.modl.proto.modl.v1.ReplayEventBatch;
import gg.modl.proto.modl.v1.ReplayFrame;
import gg.modl.proto.modl.v1.ReplayHeader;
import gg.modl.proto.modl.v1.ReplayItemPickup;
import gg.modl.proto.modl.v1.ReplayKeyframe;
import gg.modl.proto.modl.v1.ReplayPlayerInfoRemove;
import gg.modl.proto.modl.v1.ReplayPlayerInfoUpdate;
import gg.modl.proto.modl.v1.ReplayTargetAttributes;
import gg.modl.proto.modl.v1.ReplayTargetExperience;
import gg.modl.proto.modl.v1.ReplayTargetGameMode;
import gg.modl.proto.modl.v1.ReplayTargetHealth;
import gg.modl.proto.modl.v1.ReplayTargetInventory;
import gg.modl.proto.modl.v1.ReplayTargetSelectedSlot;
import gg.modl.proto.modl.v1.ReplayWeather;
import gg.modl.proto.modl.v1.ReplayWorldTime;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

final class EventEmitter extends ForwardingVisitor {
    private static final long KEYFRAME_INTERVAL_MS = 10_000L;
    private static final long BATCH_SPAN_MS = 1_000L;
    private static final int BATCH_EVENTS = 256;
    private static final long TELEPORT_DISTANCE = 8L * 4096L;

    private final WorldModel model;
    private final BoundedReplayWriter writer;
    private final FileTables tables;
    private final ModelProtos protos;
    private final SectionWriter sections;
    private final Map<Integer, WorldSegment> segments;
    private final long windowStart;
    private final long startEpochMs;
    private final long captureTime;
    private final Function<ReplayHeader.Builder, ReplayHeader> header;
    private final List<ReplayEvent> batch = new ArrayList<>();
    private final Set<UUID> announcedPlayers = new HashSet<>();
    private boolean started;
    private long start;
    private long batchStart;
    private long lastEventTime;
    private long nextKeyframe;
    private long eventCount;
    private long keyframeCount;

    EventEmitter(WorldModel model, BoundedReplayWriter writer, FileTables tables, ModelProtos protos, SectionWriter sections,
                 Map<Integer, WorldSegment> segments, long windowStart, long startEpochMs, long captureTime,
                 Function<ReplayHeader.Builder, ReplayHeader> header) {
        super(model);
        this.model = model;
        this.writer = writer;
        this.tables = tables;
        this.protos = protos;
        this.sections = sections;
        this.segments = segments;
        this.windowStart = windowStart;
        this.startEpochMs = startEpochMs;
        this.captureTime = captureTime;
        this.header = header;
    }

    long eventCount() {
        return eventCount;
    }

    long keyframeCount() {
        return keyframeCount;
    }

    int durationMs(long captureTime) {
        return started ? (int) Math.max(0L, captureTime - start) : 0;
    }

    void finish(long captureTime) throws IOException {
        if (!started) begin(Math.max(windowStart, captureTime));
        flushBatch();
        if (keyframeCount == 0) writeKeyframe(start);
    }

    private boolean live(long time) {
        if (started) {
            beforeEvent(time);
            return true;
        }
        if (time < windowStart || model.getSegment() < 0) return false;
        begin(time);
        beforeEvent(time);
        return true;
    }

    private void begin(long time) {
        started = true;
        start = time;
        nextKeyframe = time;
        try {
            ReplayHeader built = header.apply(ReplayHeader.newBuilder()
                    .setWindowStartEpochMs(startEpochMs + time)
                    .setDurationMs((int) Math.max(0L, captureTime - time)));
            writer.write(ReplayFrame.newBuilder().setHeader(built).build());
            sections.write(writer, tables);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void beforeEvent(long time) {
        try {
            if (time >= nextKeyframe) {
                flushBatch();
                writeKeyframe(nextKeyframe);
                while (nextKeyframe <= time) nextKeyframe += KEYFRAME_INTERVAL_MS;
            }
            if (!batch.isEmpty() && (batch.size() >= BATCH_EVENTS || time - batchStart >= BATCH_SPAN_MS)) flushBatch();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeKeyframe(long time) throws IOException {
        ReplayKeyframe.Builder keyframe = ReplayKeyframe.newBuilder()
                .setTimeMs(relative(time))
                .setWorld(Math.max(0, model.getSegment()))
                .setTimeOfDay(model.getTimeOfDay())
                .setRain(model.getRain())
                .setThunder(model.getThunder())
                .setTarget(protos.target(model.getTarget(), model.targetEntity()));
        announcedPlayers.clear();
        for (ModelPlayer player : model.keyframePlayers()) {
            keyframe.addPlayers(ModelProtos.player(player));
            announcedPlayers.add(player.getUuid());
        }
        for (ModelEntity entity : model.entities()) keyframe.addEntities(protos.entity(entity));
        ReplayFrame frame = ReplayFrame.newBuilder().setKeyframe(keyframe).build();
        tables.flush(writer);
        writer.write(frame);
        keyframeCount++;
    }

    private void flushBatch() throws IOException {
        if (batch.isEmpty()) return;
        ReplayFrame frame = ReplayFrame.newBuilder()
                .setEvents(ReplayEventBatch.newBuilder().setStartMs(relative(batchStart)).addAllEvents(batch)).build();
        tables.flush(writer);
        writer.write(frame);
        batch.clear();
    }

    private void emit(long time, ReplayEvent.Builder event) {
        if (batch.isEmpty()) {
            batchStart = time;
            lastEventTime = time;
        }
        batch.add(event.setDeltaMs((int) Math.max(0L, time - lastEventTime)).build());
        lastEventTime = time;
        eventCount++;
    }

    private int relative(long time) {
        return (int) Math.max(0L, time - start);
    }

    @Override
    public void entitySpawn(long time, int entityId, int type, @Nullable UUID uuid, long x, long y, long z,
                            float yaw, float pitch, float headYaw, int data) {
        boolean live = live(time);
        super.entitySpawn(time, entityId, type, uuid, x, y, z, yaw, pitch, headYaw, data);
        if (entityId == model.getTargetEntityId() || !live) return;
        ModelEntity entity = model.entity(entityId);
        if (entity == null) return;
        announceProfile(time, entity);
        emit(time, ReplayEvent.newBuilder().setEntitySpawn(ReplayEntitySpawn.newBuilder().setEntity(protos.entity(entity))));
    }

    private void announceProfile(long time, ModelEntity entity) {
        ModelPlayer player = model.profileOf(entity);
        if (player != null && announcedPlayers.add(player.getUuid())) {
            emit(time, ReplayEvent.newBuilder().setPlayerInfo(ReplayPlayerInfoUpdate.newBuilder().setPlayer(ModelProtos.player(player))));
        }
    }

    @Override
    public void entityRemove(long time, int[] entityIds) {
        boolean live = live(time);
        List<Integer> removed = new ArrayList<>(entityIds.length);
        for (int id : entityIds) {
            if (id != model.getTargetEntityId() && model.entity(id) != null) removed.add(id);
        }
        super.entityRemove(time, entityIds);
        if (removed.isEmpty() || !live) return;
        emit(time, ReplayEvent.newBuilder().setEntityRemove(ReplayEntityRemove.newBuilder().addAllEntityIds(removed)));
    }

    @Override
    public void entityMove(long time, int entityId, int dx, int dy, int dz, boolean rotated, float yaw, float pitch, boolean onGround) {
        boolean live = live(time);
        boolean known = model.entity(entityId) != null;
        super.entityMove(time, entityId, dx, dy, dz, rotated, yaw, pitch, onGround);
        if (!known || !live) return;
        ReplayEntityMove.Builder move = ReplayEntityMove.newBuilder().setEntityId(entityId).setDx(dx).setDy(dy).setDz(dz).setOnGround(onGround);
        if (rotated) move.setYaw(yaw).setPitch(pitch);
        emit(time, ReplayEvent.newBuilder().setEntityMove(move));
    }

    @Override
    public void entityTeleport(long time, int entityId, long x, long y, long z, float yaw, float pitch, boolean onGround) {
        boolean live = live(time);
        boolean known = model.entity(entityId) != null;
        super.entityTeleport(time, entityId, x, y, z, yaw, pitch, onGround);
        if (!known || !live) return;
        emit(time, ReplayEvent.newBuilder().setEntityTeleport(ReplayEntityTeleport.newBuilder().setEntityId(entityId)
                .setPosition(ModelProtos.position(x, y, z)).setYaw(yaw).setPitch(pitch).setOnGround(onGround)));
    }

    @Override
    public void entityHead(long time, int entityId, float headYaw) {
        boolean live = live(time);
        ModelEntity entity = model.entity(entityId);
        super.entityHead(time, entityId, headYaw);
        if (entity == null || !live) return;
        emit(time, ReplayEvent.newBuilder().setEntityMove(ReplayEntityMove.newBuilder().setEntityId(entityId).setHeadYaw(headYaw)
                .setOnGround(entity.isOnGround())));
    }

    @Override
    public void entityMetadata(long time, int entityId, MetadataValues values) {
        boolean live = live(time);
        boolean known = model.entity(entityId) != null;
        super.entityMetadata(time, entityId, values);
        if (!known || !live) return;
        emit(time, ReplayEvent.newBuilder().setEntityMetadata(ReplayEntityMetadataUpdate.newBuilder().setEntityId(entityId)
                .setMetadata(protos.metadata(values))));
    }

    @Override
    public void entityEquipment(long time, int entityId, List<SlotItem> equipment) {
        boolean live = live(time);
        boolean known = model.entity(entityId) != null;
        super.entityEquipment(time, entityId, equipment);
        if (!known || !live) return;
        emit(time, ReplayEvent.newBuilder().setEntityEquipment(ReplayEntityEquipmentUpdate.newBuilder().setEntityId(entityId)
                .addAllEquipment(protos.equipmentList(equipment, new ArrayList<>()))));
    }

    @Override
    public void entityAnimation(long time, int entityId, int animation) {
        boolean live = live(time);
        super.entityAnimation(time, entityId, animation);
        if (!live) return;
        ReplayAnimation value = ReplayAnimation.forNumber(animation);
        if (value == null) return;
        emit(time, ReplayEvent.newBuilder().setEntityAnimation(ReplayEntityAnimation.newBuilder().setEntityId(entityId).setAnimation(value)));
    }

    @Override
    public void entityHurt(long time, int entityId, @Nullable Float yaw) {
        boolean live = live(time);
        super.entityHurt(time, entityId, yaw);
        if (!live) return;
        ReplayEntityHurt.Builder hurt = ReplayEntityHurt.newBuilder().setEntityId(entityId);
        if (yaw != null) hurt.setYaw(yaw);
        emit(time, ReplayEvent.newBuilder().setEntityHurt(hurt));
    }

    @Override
    public void entityDeath(long time, int entityId) {
        boolean live = live(time);
        super.entityDeath(time, entityId);
        if (live) emit(time, ReplayEvent.newBuilder().setEntityDeath(ReplayEntityDeath.newBuilder().setEntityId(entityId)));
    }

    @Override
    public void entityEffect(long time, int entityId, ModelEffect effect) {
        boolean live = live(time);
        super.entityEffect(time, entityId, effect);
        if (live) {
            emit(time, ReplayEvent.newBuilder().setEntityEffect(ReplayEntityEffectUpdate.newBuilder().setEntityId(entityId)
                    .setEffect(protos.effect(effect))));
        }
    }

    @Override
    public void entityEffectRemove(long time, int entityId, int effect) {
        boolean live = live(time);
        super.entityEffectRemove(time, entityId, effect);
        if (live) {
            emit(time, ReplayEvent.newBuilder().setEntityEffectRemove(ReplayEntityEffectRemove.newBuilder().setEntityId(entityId)
                    .setEffect(tables.name(effect))));
        }
    }

    @Override
    public void entityPassengers(long time, int vehicle, int[] passengers) {
        boolean live = live(time);
        super.entityPassengers(time, vehicle, passengers);
        if (!live) return;
        ReplayEntityPassengers.Builder builder = ReplayEntityPassengers.newBuilder().setVehicle(vehicle);
        for (int passenger : passengers) builder.addPassengers(passenger);
        emit(time, ReplayEvent.newBuilder().setEntityPassengers(builder));
    }

    @Override
    public void itemPickup(long time, int collected, int collector, int count) {
        boolean live = live(time);
        super.itemPickup(time, collected, collector, count);
        if (live) {
            emit(time, ReplayEvent.newBuilder().setItemPickup(ReplayItemPickup.newBuilder().setCollected(collected)
                    .setCollector(collector).setCount(count)));
        }
    }

    @Override
    public void blockChange(long time, int x, int y, int z, int state) {
        boolean live = live(time);
        super.blockChange(time, x, y, z, state);
        if (live && sections.wrote(model.getSegment(), x, z)) {
            emit(time, ReplayEvent.newBuilder().setBlockChange(ReplayBlockChange.newBuilder().setX(x).setY(y).setZ(z)
                    .setState(tables.block(state))));
        }
    }

    @Override
    public void blockBreakProgress(long time, int entityId, int x, int y, int z, int stage) {
        boolean live = live(time);
        super.blockBreakProgress(time, entityId, x, y, z, stage);
        if (live) {
            emit(time, ReplayEvent.newBuilder().setBlockBreakProgress(ReplayBlockBreakProgress.newBuilder().setEntityId(entityId)
                    .setX(x).setY(y).setZ(z).setStage(stage)));
        }
    }

    @Override
    public void playerInfo(long time, PlayerInfoPatch patch) {
        boolean live = live(time);
        super.playerInfo(time, patch);
        if (!live) return;
        for (ModelPlayer player : model.players()) {
            if (player.getUuid().equals(patch.getUuid())) {
                announcedPlayers.add(player.getUuid());
                emit(time, ReplayEvent.newBuilder().setPlayerInfo(ReplayPlayerInfoUpdate.newBuilder().setPlayer(ModelProtos.player(player))));
                return;
            }
        }
    }

    @Override
    public void playerInfoRemove(long time, List<UUID> uuids) {
        boolean live = live(time);
        super.playerInfoRemove(time, uuids);
        if (!live) return;
        ReplayPlayerInfoRemove.Builder builder = ReplayPlayerInfoRemove.newBuilder();
        for (UUID uuid : uuids) {
            builder.addUuids(uuid.toString());
            announcedPlayers.remove(uuid);
        }
        emit(time, ReplayEvent.newBuilder().setPlayerInfoRemove(builder));
    }

    @Override
    public void chat(long time, int kind, String componentJson, @Nullable UUID sender) {
        boolean live = live(time);
        super.chat(time, kind, componentJson, sender);
        if (!live) return;
        ReplayChatKind value = ReplayChatKind.forNumber(kind);
        ReplayChat.Builder chat = ReplayChat.newBuilder()
                .setKind(value == null ? ReplayChatKind.REPLAY_CHAT_KIND_SYSTEM : value)
                .setComponentJson(componentJson);
        if (sender != null) chat.setSenderUuid(sender.toString());
        emit(time, ReplayEvent.newBuilder().setChat(chat));
    }

    @Override
    public void targetHealth(long time, float health, int food, float saturation) {
        boolean live = live(time);
        super.targetHealth(time, health, food, saturation);
        if (live) {
            emit(time, ReplayEvent.newBuilder().setTargetHealth(ReplayTargetHealth.newBuilder().setHealth(health).setFood(food)
                    .setSaturation(saturation)));
        }
    }

    @Override
    public void targetAttributes(long time, @Nullable Float maxHealth, @Nullable Integer armor) {
        boolean live = live(time);
        super.targetAttributes(time, maxHealth, armor);
        if (!live) return;
        ReplayTargetAttributes.Builder builder = ReplayTargetAttributes.newBuilder();
        if (maxHealth != null) builder.setMaxHealth(maxHealth);
        if (armor != null) builder.setArmor(armor);
        emit(time, ReplayEvent.newBuilder().setTargetAttributes(builder));
    }

    @Override
    public void targetExperience(long time, float progress, int level, int total) {
        boolean live = live(time);
        super.targetExperience(time, progress, level, total);
        if (live) {
            emit(time, ReplayEvent.newBuilder().setTargetExperience(ReplayTargetExperience.newBuilder().setProgress(progress)
                    .setLevel(level).setTotal(total)));
        }
    }

    @Override
    public void targetSlot(long time, int slot) {
        boolean live = live(time);
        int before = model.getTarget().getSelectedSlot();
        super.targetSlot(time, slot);
        if (before != slot && live) {
            emit(time, ReplayEvent.newBuilder().setTargetSelectedSlot(ReplayTargetSelectedSlot.newBuilder().setSlot(slot)));
        }
    }

    @Override
    public void targetInventory(long time, boolean replaceAll, List<SlotItem> slots) {
        boolean live = live(time);
        super.targetInventory(time, replaceAll, slots);
        if (!live) return;
        ReplayTargetInventory.Builder builder = ReplayTargetInventory.newBuilder().setReplaceAll(replaceAll);
        for (SlotItem slot : slots) builder.addSlots(protos.slot(slot));
        emit(time, ReplayEvent.newBuilder().setTargetInventory(builder));
    }

    @Override
    public void targetGameMode(long time, int gameMode) {
        boolean live = live(time);
        super.targetGameMode(time, gameMode);
        if (live) {
            emit(time, ReplayEvent.newBuilder().setTargetGameMode(ReplayTargetGameMode.newBuilder()
                    .setGameMode(ModelProtos.gameMode(gameMode))));
        }
    }

    @Override
    public void worldSegment(long time, int segment, int targetEntityId) {
        boolean live = live(time);
        int previous = model.getSegment();
        List<Integer> cleared = model.entityIdsExceptTarget();
        super.worldSegment(time, segment, targetEntityId);
        if (!live) return;
        if (previous != segment) {
            emit(time, ReplayEvent.newBuilder().setDimensionChange(ReplayDimensionChange.newBuilder()
                    .setDimension(ModelProtos.dimension(segments.get(segment)))));
        } else if (!cleared.isEmpty()) {
            emit(time, ReplayEvent.newBuilder().setEntityRemove(ReplayEntityRemove.newBuilder().addAllEntityIds(cleared)));
        }
    }

    @Override
    public void worldTime(long time, long timeOfDay) {
        boolean live = live(time);
        super.worldTime(time, timeOfDay);
        if (live) emit(time, ReplayEvent.newBuilder().setWorldTime(ReplayWorldTime.newBuilder().setTimeOfDay(timeOfDay)));
    }

    @Override
    public void weather(long time, float rain, float thunder) {
        boolean live = live(time);
        super.weather(time, rain, thunder);
        if (live) emit(time, ReplayEvent.newBuilder().setWeather(ReplayWeather.newBuilder().setRain(rain).setThunder(thunder)));
    }

    @Override
    public void targetMove(long time, int mask, long x, long y, long z, float yaw, float pitch, boolean onGround) {
        boolean live = live(time);
        ModelEntity self = model.targetEntity();
        long beforeX = self.getX();
        long beforeY = self.getY();
        long beforeZ = self.getZ();
        float beforeYaw = self.getYaw();
        float beforePitch = self.getPitch();
        boolean beforeGround = self.isOnGround();
        super.targetMove(time, mask, x, y, z, yaw, pitch, onGround);
        if (!live) return;
        ModelEntity after = model.targetEntity();
        long dx = after.getX() - beforeX;
        long dy = after.getY() - beforeY;
        long dz = after.getZ() - beforeZ;
        boolean rotated = after.getYaw() != beforeYaw || after.getPitch() != beforePitch;
        boolean relative = (mask & (Records.MOVE_RELATIVE_X | Records.MOVE_RELATIVE_Y | Records.MOVE_RELATIVE_Z)) != 0;
        boolean far = Math.abs(dx) > TELEPORT_DISTANCE || Math.abs(dy) > TELEPORT_DISTANCE || Math.abs(dz) > TELEPORT_DISTANCE;
        if (far || (relative && (dx != 0 || dy != 0 || dz != 0))) {
            emit(time, ReplayEvent.newBuilder().setEntityTeleport(ReplayEntityTeleport.newBuilder().setEntityId(after.getEntityId())
                    .setPosition(ModelProtos.position(after.getX(), after.getY(), after.getZ()))
                    .setYaw(after.getYaw()).setPitch(after.getPitch()).setOnGround(after.isOnGround())));
            return;
        }
        if (dx == 0 && dy == 0 && dz == 0 && !rotated && beforeGround == after.isOnGround()) return;
        ReplayEntityMove.Builder move = ReplayEntityMove.newBuilder().setEntityId(after.getEntityId())
                .setDx((int) dx).setDy((int) dy).setDz((int) dz).setOnGround(after.isOnGround());
        if (rotated) move.setYaw(after.getYaw()).setPitch(after.getPitch()).setHeadYaw(after.getHeadYaw());
        emit(time, ReplayEvent.newBuilder().setEntityMove(move));
    }
}
