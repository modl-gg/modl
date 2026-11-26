package gg.modl.minecraft.replay.recording;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

final class RecordEncoder {
    private RecordEncoder() {
    }

    private static RecordWriter begin(int kind, long time) {
        return new RecordWriter().begin(kind, time);
    }

    static RecordWriter entitySpawn(long time, int entityId, int type, @Nullable UUID uuid, long x, long y, long z,
                                    float yaw, float pitch, float headYaw, int data) {
        RecordWriter out = begin(RecordKind.ENTITY_SPAWN, time).writeVarInt(entityId).writeVarInt(type);
        Records.writeOptionalUuid(out, uuid);
        return out.writeSignedVarLong(x).writeSignedVarLong(y).writeSignedVarLong(z)
                .writeFloat(yaw).writeFloat(pitch).writeFloat(headYaw).writeSignedVarInt(data);
    }

    static RecordWriter entityRemove(long time, int[] entityIds) {
        RecordWriter out = begin(RecordKind.ENTITY_REMOVE, time);
        Records.writeIds(out, entityIds);
        return out;
    }

    static RecordWriter entityMove(long time, int entityId, int dx, int dy, int dz, boolean rotated, float yaw, float pitch,
                                   boolean onGround) {
        RecordWriter out = begin(RecordKind.ENTITY_MOVE, time).writeVarInt(entityId)
                .writeSignedVarInt(dx).writeSignedVarInt(dy).writeSignedVarInt(dz)
                .writeByte((rotated ? 1 : 0) | (onGround ? 2 : 0));
        if (rotated) out.writeFloat(yaw).writeFloat(pitch);
        return out;
    }

    static RecordWriter entityTeleport(long time, int entityId, long x, long y, long z, float yaw, float pitch, boolean onGround) {
        return begin(RecordKind.ENTITY_TELEPORT, time).writeVarInt(entityId)
                .writeSignedVarLong(x).writeSignedVarLong(y).writeSignedVarLong(z)
                .writeFloat(yaw).writeFloat(pitch).writeBoolean(onGround);
    }

    static RecordWriter entityHead(long time, int entityId, float headYaw) {
        return begin(RecordKind.ENTITY_HEAD, time).writeVarInt(entityId).writeFloat(headYaw);
    }

    static RecordWriter entityMetadata(long time, int entityId, MetadataValues values) {
        RecordWriter out = begin(RecordKind.ENTITY_METADATA, time).writeVarInt(entityId);
        values.write(out);
        return out;
    }

    static RecordWriter entityEquipment(long time, int entityId, List<SlotItem> equipment) {
        RecordWriter out = begin(RecordKind.ENTITY_EQUIPMENT, time).writeVarInt(entityId);
        SlotItem.writeAll(out, equipment);
        return out;
    }

    static RecordWriter entityAnimation(long time, int entityId, int animation) {
        return begin(RecordKind.ENTITY_ANIMATION, time).writeVarInt(entityId).writeByte(animation);
    }

    static RecordWriter entityHurt(long time, int entityId, @Nullable Float yaw) {
        RecordWriter out = begin(RecordKind.ENTITY_HURT, time).writeVarInt(entityId).writeBoolean(yaw != null);
        if (yaw != null) out.writeFloat(yaw);
        return out;
    }

    static RecordWriter entityDeath(long time, int entityId) {
        return begin(RecordKind.ENTITY_DEATH, time).writeVarInt(entityId);
    }

    static RecordWriter entityEffect(long time, int entityId, ModelEffect effect) {
        RecordWriter out = begin(RecordKind.ENTITY_EFFECT, time).writeVarInt(entityId);
        effect.write(out);
        return out;
    }

    static RecordWriter entityEffectRemove(long time, int entityId, int effect) {
        return begin(RecordKind.ENTITY_EFFECT_REMOVE, time).writeVarInt(entityId).writeVarInt(effect);
    }

    static RecordWriter entityPassengers(long time, int vehicle, int[] passengers) {
        RecordWriter out = begin(RecordKind.ENTITY_PASSENGERS, time).writeVarInt(vehicle);
        Records.writeIds(out, passengers);
        return out;
    }

    static RecordWriter itemPickup(long time, int collected, int collector, int count) {
        return begin(RecordKind.ITEM_PICKUP, time).writeVarInt(collected).writeVarInt(collector).writeVarInt(count);
    }

    static RecordWriter blockChange(long time, int x, int y, int z, int state) {
        return begin(RecordKind.BLOCK_CHANGE, time).writeSignedVarInt(x).writeSignedVarInt(y).writeSignedVarInt(z).writeVarInt(state);
    }

    static RecordWriter blockBreakProgress(long time, int entityId, int x, int y, int z, int stage) {
        return begin(RecordKind.BLOCK_BREAK_PROGRESS, time).writeVarInt(entityId)
                .writeSignedVarInt(x).writeSignedVarInt(y).writeSignedVarInt(z).writeSignedVarInt(stage);
    }

    static RecordWriter playerInfo(long time, PlayerInfoPatch patch) {
        RecordWriter out = begin(RecordKind.PLAYER_INFO, time);
        patch.write(out);
        return out;
    }

    static RecordWriter playerInfoRemove(long time, List<UUID> uuids) {
        RecordWriter out = begin(RecordKind.PLAYER_INFO_REMOVE, time).writeVarInt(uuids.size());
        for (UUID uuid : uuids) out.writeUuid(uuid);
        return out;
    }

    static RecordWriter chat(long time, int kind, String componentJson, @Nullable UUID sender) {
        RecordWriter out = begin(RecordKind.CHAT, time).writeByte(kind).writeString(componentJson);
        Records.writeOptionalUuid(out, sender);
        return out;
    }

    static RecordWriter targetHealth(long time, float health, int food, float saturation) {
        return begin(RecordKind.TARGET_HEALTH, time).writeFloat(health).writeVarInt(Math.max(0, food)).writeFloat(saturation);
    }

    static RecordWriter targetAttributes(long time, @Nullable Float maxHealth, @Nullable Integer armor) {
        RecordWriter out = begin(RecordKind.TARGET_ATTRIBUTES, time).writeBoolean(maxHealth != null);
        if (maxHealth != null) out.writeFloat(maxHealth);
        out.writeBoolean(armor != null);
        if (armor != null) out.writeVarInt(Math.max(0, armor));
        return out;
    }

    static RecordWriter targetExperience(long time, float progress, int level, int total) {
        return begin(RecordKind.TARGET_EXPERIENCE, time).writeFloat(progress).writeVarInt(Math.max(0, level)).writeVarInt(Math.max(0, total));
    }

    static RecordWriter targetSlot(long time, int slot) {
        return begin(RecordKind.TARGET_SLOT, time).writeVarInt(Math.max(0, slot));
    }

    static RecordWriter targetInventory(long time, boolean replaceAll, List<SlotItem> slots) {
        RecordWriter out = begin(RecordKind.TARGET_INVENTORY, time).writeBoolean(replaceAll);
        SlotItem.writeAll(out, slots);
        return out;
    }

    static RecordWriter targetGameMode(long time, int gameMode) {
        return begin(RecordKind.TARGET_GAME_MODE, time).writeVarInt(gameMode);
    }

    static RecordWriter worldSegment(long time, int segment, int targetEntityId) {
        return begin(RecordKind.WORLD_SEGMENT, time).writeVarInt(segment).writeVarInt(targetEntityId);
    }

    static RecordWriter worldTime(long time, long timeOfDay) {
        return begin(RecordKind.WORLD_TIME, time).writeSignedVarLong(timeOfDay);
    }

    static RecordWriter weather(long time, float rain, float thunder) {
        return begin(RecordKind.WEATHER, time).writeFloat(rain).writeFloat(thunder);
    }

    static RecordWriter targetMove(long time, int mask, long x, long y, long z, float yaw, float pitch, boolean onGround) {
        RecordWriter out = begin(RecordKind.TARGET_MOVE, time).writeVarInt(mask);
        if ((mask & Records.MOVE_POSITION) != 0) out.writeSignedVarLong(x).writeSignedVarLong(y).writeSignedVarLong(z);
        if ((mask & Records.MOVE_ROTATION) != 0) out.writeFloat(yaw).writeFloat(pitch);
        return out.writeBoolean(onGround);
    }
}
