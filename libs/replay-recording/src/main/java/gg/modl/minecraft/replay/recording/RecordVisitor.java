package gg.modl.minecraft.replay.recording;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

interface RecordVisitor {
    void entitySpawn(long time, int entityId, int type, @Nullable UUID uuid, long x, long y, long z,
                     float yaw, float pitch, float headYaw, int data);

    void entityRemove(long time, int[] entityIds);

    void entityMove(long time, int entityId, int dx, int dy, int dz, boolean rotated, float yaw, float pitch, boolean onGround);

    void entityTeleport(long time, int entityId, long x, long y, long z, float yaw, float pitch, boolean onGround);

    void entityHead(long time, int entityId, float headYaw);

    void entityMetadata(long time, int entityId, MetadataValues values);

    void entityEquipment(long time, int entityId, List<SlotItem> equipment);

    void entityAnimation(long time, int entityId, int animation);

    void entityHurt(long time, int entityId, @Nullable Float yaw);

    void entityDeath(long time, int entityId);

    void entityEffect(long time, int entityId, ModelEffect effect);

    void entityEffectRemove(long time, int entityId, int effect);

    void entityPassengers(long time, int vehicle, int[] passengers);

    void itemPickup(long time, int collected, int collector, int count);

    void blockChange(long time, int x, int y, int z, int state);

    void blockBreakProgress(long time, int entityId, int x, int y, int z, int stage);

    void playerInfo(long time, PlayerInfoPatch patch);

    void playerInfoRemove(long time, List<UUID> uuids);

    void chat(long time, int kind, String componentJson, @Nullable UUID sender);

    void targetHealth(long time, float health, int food, float saturation);

    void targetAttributes(long time, @Nullable Float maxHealth, @Nullable Integer armor);

    void targetExperience(long time, float progress, int level, int total);

    void targetSlot(long time, int slot);

    void targetInventory(long time, boolean replaceAll, List<SlotItem> slots);

    void targetGameMode(long time, int gameMode);

    void worldSegment(long time, int segment, int targetEntityId);

    void worldTime(long time, long timeOfDay);

    void weather(long time, float rain, float thunder);

    void targetMove(long time, int mask, long x, long y, long z, float yaw, float pitch, boolean onGround);
}
