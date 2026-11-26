package gg.modl.minecraft.replay.recording;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

class ForwardingVisitor implements RecordVisitor {
    private final RecordVisitor delegate;

    ForwardingVisitor(RecordVisitor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void entitySpawn(long time, int entityId, int type, @Nullable UUID uuid, long x, long y, long z, float yaw, float pitch, float headYaw, int data) {
        delegate.entitySpawn(time, entityId, type, uuid, x, y, z, yaw, pitch, headYaw, data);
    }

    @Override
    public void entityRemove(long time, int[] entityIds) {
        delegate.entityRemove(time, entityIds);
    }

    @Override
    public void entityMove(long time, int entityId, int dx, int dy, int dz, boolean rotated, float yaw, float pitch, boolean onGround) {
        delegate.entityMove(time, entityId, dx, dy, dz, rotated, yaw, pitch, onGround);
    }

    @Override
    public void entityTeleport(long time, int entityId, long x, long y, long z, float yaw, float pitch, boolean onGround) {
        delegate.entityTeleport(time, entityId, x, y, z, yaw, pitch, onGround);
    }

    @Override
    public void entityHead(long time, int entityId, float headYaw) {
        delegate.entityHead(time, entityId, headYaw);
    }

    @Override
    public void entityMetadata(long time, int entityId, MetadataValues values) {
        delegate.entityMetadata(time, entityId, values);
    }

    @Override
    public void entityEquipment(long time, int entityId, List<SlotItem> equipment) {
        delegate.entityEquipment(time, entityId, equipment);
    }

    @Override
    public void entityAnimation(long time, int entityId, int animation) {
        delegate.entityAnimation(time, entityId, animation);
    }

    @Override
    public void entityHurt(long time, int entityId, @Nullable Float yaw) {
        delegate.entityHurt(time, entityId, yaw);
    }

    @Override
    public void entityDeath(long time, int entityId) {
        delegate.entityDeath(time, entityId);
    }

    @Override
    public void entityEffect(long time, int entityId, ModelEffect effect) {
        delegate.entityEffect(time, entityId, effect);
    }

    @Override
    public void entityEffectRemove(long time, int entityId, int effect) {
        delegate.entityEffectRemove(time, entityId, effect);
    }

    @Override
    public void entityPassengers(long time, int vehicle, int[] passengers) {
        delegate.entityPassengers(time, vehicle, passengers);
    }

    @Override
    public void itemPickup(long time, int collected, int collector, int count) {
        delegate.itemPickup(time, collected, collector, count);
    }

    @Override
    public void blockChange(long time, int x, int y, int z, int state) {
        delegate.blockChange(time, x, y, z, state);
    }

    @Override
    public void blockBreakProgress(long time, int entityId, int x, int y, int z, int stage) {
        delegate.blockBreakProgress(time, entityId, x, y, z, stage);
    }

    @Override
    public void playerInfo(long time, PlayerInfoPatch patch) {
        delegate.playerInfo(time, patch);
    }

    @Override
    public void playerInfoRemove(long time, List<UUID> uuids) {
        delegate.playerInfoRemove(time, uuids);
    }

    @Override
    public void chat(long time, int kind, String componentJson, @Nullable UUID sender) {
        delegate.chat(time, kind, componentJson, sender);
    }

    @Override
    public void targetHealth(long time, float health, int food, float saturation) {
        delegate.targetHealth(time, health, food, saturation);
    }

    @Override
    public void targetAttributes(long time, @Nullable Float maxHealth, @Nullable Integer armor) {
        delegate.targetAttributes(time, maxHealth, armor);
    }

    @Override
    public void targetExperience(long time, float progress, int level, int total) {
        delegate.targetExperience(time, progress, level, total);
    }

    @Override
    public void targetSlot(long time, int slot) {
        delegate.targetSlot(time, slot);
    }

    @Override
    public void targetInventory(long time, boolean replaceAll, List<SlotItem> slots) {
        delegate.targetInventory(time, replaceAll, slots);
    }

    @Override
    public void targetGameMode(long time, int gameMode) {
        delegate.targetGameMode(time, gameMode);
    }

    @Override
    public void worldSegment(long time, int segment, int targetEntityId) {
        delegate.worldSegment(time, segment, targetEntityId);
    }

    @Override
    public void worldTime(long time, long timeOfDay) {
        delegate.worldTime(time, timeOfDay);
    }

    @Override
    public void weather(long time, float rain, float thunder) {
        delegate.weather(time, rain, thunder);
    }

    @Override
    public void targetMove(long time, int mask, long x, long y, long z, float yaw, float pitch, boolean onGround) {
        delegate.targetMove(time, mask, x, y, z, yaw, pitch, onGround);
    }
}
