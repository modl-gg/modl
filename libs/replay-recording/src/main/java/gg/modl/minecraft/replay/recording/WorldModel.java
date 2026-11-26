package gg.modl.minecraft.replay.recording;

import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class WorldModel implements RecordVisitor {
    private static final long BASE_BYTES = 1024L;
    private static final long ENTITY_BYTES = 320L;
    private static final long PLAYER_BYTES = 192L;
    private final Map<Integer, ModelEntity> entities = new LinkedHashMap<>();
    private final Map<UUID, ModelPlayer> players = new LinkedHashMap<>();
    private final int playerType;
    private final UUID targetUuid;
    @Getter private ModelTarget target = new ModelTarget();
    @Getter private int segment;
    @Getter private int targetEntityId;
    @Getter private long timeOfDay = -1;
    @Getter private float rain;
    @Getter private float thunder;
    private @Nullable BlockListener blockListener;

    WorldModel(int playerType, UUID targetUuid, int segment, int targetEntityId) {
        this.playerType = playerType;
        this.targetUuid = targetUuid;
        this.segment = segment;
        this.targetEntityId = targetEntityId;
        entities.put(targetEntityId, new ModelEntity(targetEntityId, playerType, targetUuid, 0));
    }

    void listen(BlockListener listener) {
        blockListener = listener;
    }

    WorldModel copy() {
        WorldModel copy = new WorldModel(playerType, targetUuid, segment, targetEntityId);
        copy.entities.clear();
        for (Map.Entry<Integer, ModelEntity> entry : entities.entrySet()) copy.entities.put(entry.getKey(), entry.getValue().copy());
        for (Map.Entry<UUID, ModelPlayer> entry : players.entrySet()) copy.players.put(entry.getKey(), entry.getValue().copy());
        copy.target = target.copy();
        copy.timeOfDay = timeOfDay;
        copy.rain = rain;
        copy.thunder = thunder;
        return copy;
    }

    Collection<ModelEntity> entities() {
        return entities.values();
    }

    Collection<ModelPlayer> players() {
        return players.values();
    }

    List<ModelPlayer> keyframePlayers() {
        Set<UUID> nearby = new HashSet<>();
        for (ModelEntity entity : entities.values()) {
            UUID uuid = entity.getUuid();
            if (uuid != null && entity.getType() == playerType) nearby.add(uuid);
        }
        List<ModelPlayer> kept = new ArrayList<>();
        for (ModelPlayer player : players.values()) {
            UUID uuid = player.getUuid();
            if (uuid.equals(targetUuid) || (player.isListed() && nearby.contains(uuid))) kept.add(player);
        }
        return kept;
    }

    @Nullable ModelPlayer profileOf(ModelEntity entity) {
        UUID uuid = entity.getUuid();
        return uuid == null || entity.getType() != playerType ? null : players.get(uuid);
    }

    long estimatedBytes() {
        long bytes = BASE_BYTES + entities.size() * ENTITY_BYTES;
        for (ModelPlayer player : players.values()) {
            bytes += PLAYER_BYTES + 2L * (player.getName().length() + player.getDisplayName().length());
        }
        return bytes;
    }

    @Nullable ModelEntity entity(int entityId) {
        return entities.get(entityId);
    }

    ModelEntity targetEntity() {
        ModelEntity entity = entities.get(targetEntityId);
        if (entity == null) {
            entity = new ModelEntity(targetEntityId, playerType, targetUuid, 0);
            entities.put(targetEntityId, entity);
        }
        return entity;
    }

    @Override
    public void entitySpawn(long time, int entityId, int type, @Nullable UUID uuid, long x, long y, long z,
                            float yaw, float pitch, float headYaw, int data) {
        if (entityId == targetEntityId) return;
        ModelEntity entity = new ModelEntity(entityId, type, uuid, data);
        entity.placeAt(x, y, z);
        entity.rotate(yaw, pitch);
        entity.turnHead(headYaw);
        entities.put(entityId, entity);
    }

    @Override
    public void entityRemove(long time, int[] entityIds) {
        for (int entityId : entityIds) {
            if (entityId != targetEntityId) entities.remove(entityId);
        }
    }

    @Override
    public void entityMove(long time, int entityId, int dx, int dy, int dz, boolean rotated, float yaw, float pitch, boolean onGround) {
        ModelEntity entity = entities.get(entityId);
        if (entity == null) return;
        entity.moveBy(dx, dy, dz);
        if (rotated) entity.rotate(yaw, pitch);
        entity.land(onGround);
    }

    @Override
    public void entityTeleport(long time, int entityId, long x, long y, long z, float yaw, float pitch, boolean onGround) {
        ModelEntity entity = entities.get(entityId);
        if (entity == null) return;
        entity.placeAt(x, y, z);
        entity.rotate(yaw, pitch);
        entity.land(onGround);
    }

    @Override
    public void entityHead(long time, int entityId, float headYaw) {
        ModelEntity entity = entities.get(entityId);
        if (entity != null) entity.turnHead(headYaw);
    }

    @Override
    public void entityMetadata(long time, int entityId, MetadataValues values) {
        ModelEntity entity = entities.get(entityId);
        if (entity != null) entity.updateMetadata(values);
    }

    @Override
    public void entityEquipment(long time, int entityId, List<SlotItem> equipment) {
        ModelEntity entity = entities.get(entityId);
        if (entity == null) return;
        for (SlotItem slot : equipment) entity.equip(slot.getSlot(), slot.getItem());
    }

    @Override
    public void entityAnimation(long time, int entityId, int animation) {
    }

    @Override
    public void entityHurt(long time, int entityId, @Nullable Float yaw) {
    }

    @Override
    public void entityDeath(long time, int entityId) {
    }

    @Override
    public void entityEffect(long time, int entityId, ModelEffect effect) {
        ModelEntity entity = entities.get(entityId);
        if (entity != null) entity.addEffect(effect);
    }

    @Override
    public void entityEffectRemove(long time, int entityId, int effect) {
        ModelEntity entity = entities.get(entityId);
        if (entity != null) entity.removeEffect(effect);
    }

    @Override
    public void entityPassengers(long time, int vehicle, int[] passengers) {
        for (ModelEntity entity : entities.values()) {
            Integer current = entity.getVehicle();
            if (current != null && current == vehicle) entity.ride(null);
        }
        for (int passenger : passengers) {
            ModelEntity entity = entities.get(passenger);
            if (entity != null) entity.ride(vehicle);
        }
    }

    @Override
    public void itemPickup(long time, int collected, int collector, int count) {
        entities.remove(collected);
    }

    @Override
    public void blockChange(long time, int x, int y, int z, int state) {
        BlockListener listener = blockListener;
        if (listener != null) listener.changed(segment, time, x, y, z, state);
    }

    @Override
    public void blockBreakProgress(long time, int entityId, int x, int y, int z, int stage) {
    }

    @Override
    public void playerInfo(long time, PlayerInfoPatch patch) {
        ModelPlayer player = players.get(patch.getUuid());
        if (player == null) {
            if (patch.getName() == null) return;
            player = new ModelPlayer(patch.getUuid());
            players.put(patch.getUuid(), player);
        }
        player.apply(patch);
        if (patch.getUuid().equals(targetUuid)) {
            Integer mode = patch.getGameMode();
            if (mode != null) target.gameMode(mode);
        }
    }

    @Override
    public void playerInfoRemove(long time, List<UUID> uuids) {
        for (UUID uuid : uuids) players.remove(uuid);
    }

    @Override
    public void chat(long time, int kind, String componentJson, @Nullable UUID sender) {
    }

    @Override
    public void targetHealth(long time, float health, int food, float saturation) {
        target.health(health, food, saturation);
    }

    @Override
    public void targetAttributes(long time, @Nullable Float maxHealth, @Nullable Integer armor) {
        target.attributes(maxHealth, armor);
    }

    @Override
    public void targetExperience(long time, float progress, int level, int total) {
        target.experience(progress, level, total);
    }

    @Override
    public void targetSlot(long time, int slot) {
        target.select(slot);
    }

    @Override
    public void targetInventory(long time, boolean replaceAll, List<SlotItem> slots) {
        target.inventory(replaceAll, slots);
    }

    @Override
    public void targetGameMode(long time, int gameMode) {
        target.gameMode(gameMode);
    }

    @Override
    public void worldSegment(long time, int newSegment, int newTargetEntityId) {
        ModelEntity previous = targetEntity();
        segment = newSegment;
        targetEntityId = newTargetEntityId;
        entities.clear();
        ModelEntity self = new ModelEntity(newTargetEntityId, playerType, targetUuid, 0);
        self.placeAt(previous.getX(), previous.getY(), previous.getZ());
        self.rotate(previous.getYaw(), previous.getPitch());
        for (Map.Entry<Integer, @Nullable ModelItem> slot : previous.getEquipment().entrySet()) self.equip(slot.getKey(), slot.getValue());
        entities.put(newTargetEntityId, self);
    }

    @Override
    public void worldTime(long time, long newTimeOfDay) {
        timeOfDay = newTimeOfDay;
    }

    @Override
    public void weather(long time, float newRain, float newThunder) {
        rain = newRain;
        thunder = newThunder;
    }

    @Override
    public void targetMove(long time, int mask, long x, long y, long z, float yaw, float pitch, boolean onGround) {
        ModelEntity self = targetEntity();
        if ((mask & Records.MOVE_POSITION) != 0) {
            long newX = (mask & Records.MOVE_RELATIVE_X) != 0 ? self.getX() + x : x;
            long newY = (mask & Records.MOVE_RELATIVE_Y) != 0 ? self.getY() + y : y;
            long newZ = (mask & Records.MOVE_RELATIVE_Z) != 0 ? self.getZ() + z : z;
            self.placeAt(newX, newY, newZ);
        }
        if ((mask & Records.MOVE_ROTATION) != 0) {
            float newYaw = (mask & Records.MOVE_RELATIVE_YAW) != 0 ? self.getYaw() + yaw : yaw;
            float newPitch = (mask & Records.MOVE_RELATIVE_PITCH) != 0 ? self.getPitch() + pitch : pitch;
            self.rotate(newYaw, newPitch);
            self.turnHead(newYaw);
        }
        self.land(onGround);
    }

    List<Integer> entityIdsExceptTarget() {
        List<Integer> ids = new ArrayList<>();
        for (Integer id : entities.keySet()) {
            if (id != targetEntityId) ids.add(id);
        }
        return ids;
    }

    interface BlockListener {
        void changed(int segment, long time, int x, int y, int z, int state);
    }
}
