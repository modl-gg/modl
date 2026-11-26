package gg.modl.minecraft.replay.recording;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class Records {
    static final int MOVE_POSITION = 1;
    static final int MOVE_ROTATION = 1 << 1;
    static final int MOVE_RELATIVE_X = 1 << 2;
    static final int MOVE_RELATIVE_Y = 1 << 3;
    static final int MOVE_RELATIVE_Z = 1 << 4;
    static final int MOVE_RELATIVE_YAW = 1 << 5;
    static final int MOVE_RELATIVE_PITCH = 1 << 6;

    private Records() {
    }

    static long readTime(RecordReader in) {
        return in.readVarLong();
    }

    static void writeIds(RecordWriter out, int[] ids) {
        out.writeVarInt(ids.length);
        for (int id : ids) out.writeVarInt(id);
    }

    private static int[] readIds(RecordReader in) {
        int count = in.readVarInt();
        int[] ids = new int[count];
        for (int i = 0; i < count; i++) ids[i] = in.readVarInt();
        return ids;
    }

    static void writeOptionalUuid(RecordWriter out, @Nullable UUID uuid) {
        out.writeBoolean(uuid != null);
        if (uuid != null) out.writeUuid(uuid);
    }

    private static @Nullable UUID readOptionalUuid(RecordReader in) {
        return in.readBoolean() ? in.readUuid() : null;
    }

    static void decode(RecordReader in, RecordVisitor visitor) {
        int kind = in.readByte();
        long time = readTime(in);
        switch (kind) {
            case RecordKind.ENTITY_SPAWN: {
                int id = in.readVarInt();
                int type = in.readVarInt();
                UUID uuid = readOptionalUuid(in);
                long x = in.readSignedVarLong();
                long y = in.readSignedVarLong();
                long z = in.readSignedVarLong();
                float yaw = in.readFloat();
                float pitch = in.readFloat();
                float head = in.readFloat();
                visitor.entitySpawn(time, id, type, uuid, x, y, z, yaw, pitch, head, in.readSignedVarInt());
                return;
            }
            case RecordKind.ENTITY_REMOVE:
                visitor.entityRemove(time, readIds(in));
                return;
            case RecordKind.ENTITY_MOVE: {
                int id = in.readVarInt();
                int dx = in.readSignedVarInt();
                int dy = in.readSignedVarInt();
                int dz = in.readSignedVarInt();
                int flags = in.readByte();
                boolean rotated = (flags & 1) != 0;
                float yaw = rotated ? in.readFloat() : 0F;
                float pitch = rotated ? in.readFloat() : 0F;
                visitor.entityMove(time, id, dx, dy, dz, rotated, yaw, pitch, (flags & 2) != 0);
                return;
            }
            case RecordKind.ENTITY_TELEPORT: {
                int id = in.readVarInt();
                long x = in.readSignedVarLong();
                long y = in.readSignedVarLong();
                long z = in.readSignedVarLong();
                float yaw = in.readFloat();
                float pitch = in.readFloat();
                visitor.entityTeleport(time, id, x, y, z, yaw, pitch, in.readBoolean());
                return;
            }
            case RecordKind.ENTITY_HEAD: {
                int id = in.readVarInt();
                visitor.entityHead(time, id, in.readFloat());
                return;
            }
            case RecordKind.ENTITY_METADATA: {
                int id = in.readVarInt();
                visitor.entityMetadata(time, id, MetadataValues.read(in));
                return;
            }
            case RecordKind.ENTITY_EQUIPMENT: {
                int id = in.readVarInt();
                visitor.entityEquipment(time, id, SlotItem.readAll(in));
                return;
            }
            case RecordKind.ENTITY_ANIMATION: {
                int id = in.readVarInt();
                visitor.entityAnimation(time, id, in.readByte());
                return;
            }
            case RecordKind.ENTITY_HURT: {
                int id = in.readVarInt();
                Float yaw = in.readBoolean() ? in.readFloat() : null;
                visitor.entityHurt(time, id, yaw);
                return;
            }
            case RecordKind.ENTITY_DEATH:
                visitor.entityDeath(time, in.readVarInt());
                return;
            case RecordKind.ENTITY_EFFECT: {
                int id = in.readVarInt();
                visitor.entityEffect(time, id, ModelEffect.read(in));
                return;
            }
            case RecordKind.ENTITY_EFFECT_REMOVE: {
                int id = in.readVarInt();
                visitor.entityEffectRemove(time, id, in.readVarInt());
                return;
            }
            case RecordKind.ENTITY_PASSENGERS: {
                int vehicle = in.readVarInt();
                visitor.entityPassengers(time, vehicle, readIds(in));
                return;
            }
            case RecordKind.ITEM_PICKUP: {
                int collected = in.readVarInt();
                int collector = in.readVarInt();
                visitor.itemPickup(time, collected, collector, in.readVarInt());
                return;
            }
            case RecordKind.BLOCK_CHANGE: {
                int x = in.readSignedVarInt();
                int y = in.readSignedVarInt();
                int z = in.readSignedVarInt();
                visitor.blockChange(time, x, y, z, in.readVarInt());
                return;
            }
            case RecordKind.BLOCK_BREAK_PROGRESS: {
                int entity = in.readVarInt();
                int x = in.readSignedVarInt();
                int y = in.readSignedVarInt();
                int z = in.readSignedVarInt();
                visitor.blockBreakProgress(time, entity, x, y, z, in.readSignedVarInt());
                return;
            }
            case RecordKind.PLAYER_INFO:
                visitor.playerInfo(time, PlayerInfoPatch.read(in));
                return;
            case RecordKind.PLAYER_INFO_REMOVE: {
                int count = in.readVarInt();
                List<UUID> uuids = new ArrayList<>(count);
                for (int i = 0; i < count; i++) uuids.add(in.readUuid());
                visitor.playerInfoRemove(time, uuids);
                return;
            }
            case RecordKind.CHAT: {
                int chatKind = in.readByte();
                String json = in.readString();
                visitor.chat(time, chatKind, json, readOptionalUuid(in));
                return;
            }
            case RecordKind.TARGET_HEALTH: {
                float health = in.readFloat();
                int food = in.readVarInt();
                visitor.targetHealth(time, health, food, in.readFloat());
                return;
            }
            case RecordKind.TARGET_ATTRIBUTES: {
                Float maxHealth = in.readBoolean() ? in.readFloat() : null;
                Integer armor = in.readBoolean() ? in.readVarInt() : null;
                visitor.targetAttributes(time, maxHealth, armor);
                return;
            }
            case RecordKind.TARGET_EXPERIENCE: {
                float progress = in.readFloat();
                int level = in.readVarInt();
                visitor.targetExperience(time, progress, level, in.readVarInt());
                return;
            }
            case RecordKind.TARGET_SLOT:
                visitor.targetSlot(time, in.readVarInt());
                return;
            case RecordKind.TARGET_INVENTORY: {
                boolean replaceAll = in.readBoolean();
                visitor.targetInventory(time, replaceAll, SlotItem.readAll(in));
                return;
            }
            case RecordKind.TARGET_GAME_MODE:
                visitor.targetGameMode(time, in.readVarInt());
                return;
            case RecordKind.WORLD_SEGMENT: {
                int segment = in.readVarInt();
                visitor.worldSegment(time, segment, in.readVarInt());
                return;
            }
            case RecordKind.WORLD_TIME:
                visitor.worldTime(time, in.readSignedVarLong());
                return;
            case RecordKind.WEATHER: {
                float rain = in.readFloat();
                visitor.weather(time, rain, in.readFloat());
                return;
            }
            case RecordKind.TARGET_MOVE: {
                int mask = in.readVarInt();
                long x = 0;
                long y = 0;
                long z = 0;
                if ((mask & MOVE_POSITION) != 0) {
                    x = in.readSignedVarLong();
                    y = in.readSignedVarLong();
                    z = in.readSignedVarLong();
                }
                float yaw = 0F;
                float pitch = 0F;
                if ((mask & MOVE_ROTATION) != 0) {
                    yaw = in.readFloat();
                    pitch = in.readFloat();
                }
                visitor.targetMove(time, mask, x, y, z, yaw, pitch, in.readBoolean());
                return;
            }
            default:
                throw new IllegalStateException("Unknown replay journal record " + kind);
        }
    }
}
