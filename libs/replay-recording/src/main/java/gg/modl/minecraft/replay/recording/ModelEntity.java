package gg.modl.minecraft.replay.recording;

import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Getter
final class ModelEntity {
    private final int entityId;
    private final int type;
    private final @Nullable UUID uuid;
    private final int data;
    private long x;
    private long y;
    private long z;
    private float yaw;
    private float pitch;
    private float headYaw;
    private boolean onGround;
    private @Nullable Integer vehicle;
    private MetadataValues metadata = MetadataValues.EMPTY;
    private final Map<Integer, @Nullable ModelItem> equipment = new TreeMap<>();
    private final Map<Integer, ModelEffect> effects = new TreeMap<>();

    ModelEntity(int entityId, int type, @Nullable UUID uuid, int data) {
        this.entityId = entityId;
        this.type = type;
        this.uuid = uuid;
        this.data = data;
    }

    ModelEntity copy() {
        ModelEntity copy = new ModelEntity(entityId, type, uuid, data);
        copy.x = x;
        copy.y = y;
        copy.z = z;
        copy.yaw = yaw;
        copy.pitch = pitch;
        copy.headYaw = headYaw;
        copy.onGround = onGround;
        copy.vehicle = vehicle;
        copy.metadata = metadata;
        copy.equipment.putAll(equipment);
        copy.effects.putAll(effects);
        return copy;
    }

    void placeAt(long newX, long newY, long newZ) {
        x = newX;
        y = newY;
        z = newZ;
    }

    void moveBy(int dx, int dy, int dz) {
        x += dx;
        y += dy;
        z += dz;
    }

    void rotate(float newYaw, float newPitch) {
        yaw = newYaw;
        pitch = newPitch;
    }

    void turnHead(float newHeadYaw) {
        headYaw = newHeadYaw;
    }

    void land(boolean grounded) {
        onGround = grounded;
    }

    void ride(@Nullable Integer vehicleId) {
        vehicle = vehicleId;
    }

    void updateMetadata(MetadataValues update) {
        metadata = metadata.mergedWith(update);
    }

    void equip(int slot, @Nullable ModelItem item) {
        equipment.put(slot, item);
    }

    void addEffect(ModelEffect effect) {
        effects.put(effect.getEffect(), effect);
    }

    void removeEffect(int effect) {
        effects.remove(effect);
    }
}
