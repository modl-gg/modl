package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.EntityPositionData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.potion.PotionType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockBreakAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCollectItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRemoveEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnExperienceOrb;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnLivingEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPainting;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class EntityPackets {
    static final int ANIMATION_SWING_MAIN_HAND = 1;
    static final int ANIMATION_SWING_OFF_HAND = 2;
    static final int ANIMATION_CRITICAL_HIT = 3;
    static final int ANIMATION_MAGIC_CRITICAL_HIT = 4;
    static final int ANIMATION_WAKE_UP = 5;

    private static final int STATUS_HURT = 2;
    private static final int STATUS_DEATH = 3;

    private final TranslationContext context;

    EntityPackets(TranslationContext context) {
        this.context = context;
    }

    void register(PacketHandlers handlers) {
        handlers.onSend(PacketType.Play.Server.SPAWN_ENTITY, this::spawnEntity);
        handlers.onSend(PacketType.Play.Server.SPAWN_LIVING_ENTITY, this::spawnLiving);
        handlers.onSend(PacketType.Play.Server.SPAWN_PLAYER, this::spawnPlayer);
        handlers.onSend(PacketType.Play.Server.SPAWN_PAINTING, this::spawnPainting);
        handlers.onSend(PacketType.Play.Server.SPAWN_EXPERIENCE_ORB, this::spawnOrb);
        handlers.onSend(PacketType.Play.Server.DESTROY_ENTITIES, this::destroy);
        handlers.onSend(PacketType.Play.Server.ENTITY_RELATIVE_MOVE, this::relativeMove);
        handlers.onSend(PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION, this::relativeMoveAndRotation);
        handlers.onSend(PacketType.Play.Server.ENTITY_ROTATION, this::rotation);
        handlers.onSend(PacketType.Play.Server.ENTITY_TELEPORT, this::teleport);
        handlers.onSend(PacketType.Play.Server.ENTITY_POSITION_SYNC, this::positionSync);
        handlers.onSend(PacketType.Play.Server.ENTITY_HEAD_LOOK, this::headLook);
        handlers.onSend(PacketType.Play.Server.ENTITY_METADATA, this::metadata);
        handlers.onSend(PacketType.Play.Server.ENTITY_EQUIPMENT, this::equipment);
        handlers.onSend(PacketType.Play.Server.ENTITY_ANIMATION, this::animation);
        handlers.onSend(PacketType.Play.Server.HURT_ANIMATION, this::hurt);
        handlers.onSend(PacketType.Play.Server.ENTITY_STATUS, this::status);
        handlers.onSend(PacketType.Play.Server.ENTITY_EFFECT, this::effect);
        handlers.onSend(PacketType.Play.Server.REMOVE_ENTITY_EFFECT, this::removeEffect);
        handlers.onSend(PacketType.Play.Server.SET_PASSENGERS, this::passengers);
        handlers.onSend(PacketType.Play.Server.COLLECT_ITEM, this::collect);
        handlers.onSend(PacketType.Play.Server.BLOCK_BREAK_ANIMATION, this::breakProgress);
    }

    private void spawnEntity(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerSpawnEntity packet = new WrapperPlayServerSpawnEntity(event);
        EntityType type = packet.getEntityType();
        if (type == null) return;
        spawn(recording, time, packet.getEntityId(), type, packet.getUUID().orElse(null), packet.getPosition(),
                packet.getYaw(), packet.getPitch(), packet.getHeadYaw(), packet.getData());
    }

    private void spawnLiving(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerSpawnLivingEntity packet = new WrapperPlayServerSpawnLivingEntity(event);
        EntityType type = packet.getEntityType();
        if (type == null) return;
        spawn(recording, time, packet.getEntityId(), type, packet.getEntityUUID(), packet.getPosition(),
                packet.getYaw(), packet.getPitch(), packet.getHeadPitch(), 0);
        initialMetadata(recording, time, packet.getEntityId(), type, packet.getEntityMetadata());
    }

    @SuppressWarnings("deprecation")
    private void spawnPlayer(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerSpawnPlayer packet = new WrapperPlayServerSpawnPlayer(event);
        spawn(recording, time, packet.getEntityId(), EntityTypes.PLAYER, packet.getUUID(), packet.getPosition(),
                packet.getYaw(), packet.getPitch(), packet.getYaw(), 0);
        initialMetadata(recording, time, packet.getEntityId(), EntityTypes.PLAYER, packet.getEntityMetadata());
    }

    private void spawnPainting(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerSpawnPainting packet = new WrapperPlayServerSpawnPainting(event);
        Vector3i block = packet.getPosition();
        float yaw = packet.getDirection() == null ? 0F : Math.max(0, packet.getDirection().getHorizontalIndex()) * 90F;
        spawn(recording, time, packet.getEntityId(), EntityTypes.PAINTING, packet.getUUID(),
                new Vector3d(block.getX() + 0.5, block.getY(), block.getZ() + 0.5), yaw, 0F, yaw, 0);
    }

    private void spawnOrb(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerSpawnExperienceOrb packet = new WrapperPlayServerSpawnExperienceOrb(event);
        spawn(recording, time, packet.getEntityId(), EntityTypes.EXPERIENCE_ORB, null,
                new Vector3d(packet.getX(), packet.getY(), packet.getZ()), 0F, 0F, 0F, packet.getCount());
    }

    private void spawn(Recording recording, long time, int entityId, EntityType type, @Nullable UUID uuid, Vector3d position,
                       float yaw, float pitch, float headYaw, int data) {
        recording.trackType(entityId, type);
        recording.append(RecordEncoder.entitySpawn(time, entityId, context.typeName(type), uuid,
                Fixed.of(position.getX()), Fixed.of(position.getY()), Fixed.of(position.getZ()), yaw, pitch, headYaw, data), time);
    }

    private void initialMetadata(Recording recording, long time, int entityId, EntityType type, @Nullable List<EntityData<?>> data) {
        if (data == null || data.isEmpty()) return;
        MetadataValues values = context.getMetadata().translate(data, TranslationContext.isLiving(type), TranslationContext.isPlayer(type));
        if (!values.isEmpty()) recording.append(RecordEncoder.entityMetadata(time, entityId, values), time);
    }

    private void destroy(PacketSendEvent event, Recording recording, long time) {
        int[] ids = new WrapperPlayServerDestroyEntities(event).getEntityIds();
        for (int id : ids) recording.forgetType(id);
        recording.append(RecordEncoder.entityRemove(time, ids), time);
    }

    private void relativeMove(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerEntityRelativeMove packet = new WrapperPlayServerEntityRelativeMove(event);
        recording.append(RecordEncoder.entityMove(time, packet.getEntityId(), Fixed.delta(packet.getDeltaX()),
                Fixed.delta(packet.getDeltaY()), Fixed.delta(packet.getDeltaZ()), false, 0F, 0F, packet.isOnGround()), time);
    }

    private void relativeMoveAndRotation(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerEntityRelativeMoveAndRotation packet = new WrapperPlayServerEntityRelativeMoveAndRotation(event);
        recording.append(RecordEncoder.entityMove(time, packet.getEntityId(), Fixed.delta(packet.getDeltaX()),
                Fixed.delta(packet.getDeltaY()), Fixed.delta(packet.getDeltaZ()), true, packet.getYaw(), packet.getPitch(),
                packet.isOnGround()), time);
    }

    private void rotation(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerEntityRotation packet = new WrapperPlayServerEntityRotation(event);
        recording.append(RecordEncoder.entityMove(time, packet.getEntityId(), 0, 0, 0, true, packet.getYaw(), packet.getPitch(),
                packet.isOnGround()), time);
    }

    private void teleport(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerEntityTeleport packet = new WrapperPlayServerEntityTeleport(event);
        Vector3d position = packet.getPosition();
        recording.append(RecordEncoder.entityTeleport(time, packet.getEntityId(), Fixed.of(position.getX()),
                Fixed.of(position.getY()), Fixed.of(position.getZ()), packet.getYaw(), packet.getPitch(), packet.isOnGround()), time);
    }

    private void positionSync(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerEntityPositionSync packet = new WrapperPlayServerEntityPositionSync(event);
        EntityPositionData values = packet.getValues();
        Vector3d position = values.getPosition();
        recording.append(RecordEncoder.entityTeleport(time, packet.getId(), Fixed.of(position.getX()),
                Fixed.of(position.getY()), Fixed.of(position.getZ()), values.getYaw(), values.getPitch(), packet.isOnGround()), time);
    }

    private void headLook(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerEntityHeadLook packet = new WrapperPlayServerEntityHeadLook(event);
        recording.append(RecordEncoder.entityHead(time, packet.getEntityId(), packet.getHeadYaw()), time);
    }

    private void metadata(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(event);
        int entityId = packet.getEntityId();
        EntityType type = entityId == recording.targetEntityId() ? EntityTypes.PLAYER : recording.typeOf(entityId);
        boolean living = type != null && TranslationContext.isLiving(type);
        boolean player = type != null && TranslationContext.isPlayer(type);
        MetadataValues values = context.getMetadata().translate(packet.getEntityMetadata(), living, player);
        if (!values.isEmpty()) recording.append(RecordEncoder.entityMetadata(time, entityId, values), time);
    }

    private void equipment(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerEntityEquipment packet = new WrapperPlayServerEntityEquipment(event);
        List<Equipment> equipment = packet.getEquipment();
        if (equipment == null || equipment.isEmpty()) return;
        List<SlotItem> slots = new ArrayList<>(equipment.size());
        for (Equipment entry : equipment) {
            int slot = equipmentSlot(entry.getSlot());
            if (slot > 0) slots.add(new SlotItem(slot, context.getItems().convert(entry.getItem())));
        }
        if (!slots.isEmpty()) recording.append(RecordEncoder.entityEquipment(time, packet.getEntityId(), slots), time);
    }

    static int equipmentSlot(@Nullable EquipmentSlot slot) {
        if (slot == null) return 0;
        switch (slot) {
            case MAIN_HAND: return 1;
            case OFF_HAND: return 2;
            case HELMET: return 3;
            case CHEST_PLATE: return 4;
            case LEGGINGS: return 5;
            case BOOTS: return 6;
            case BODY: return 7;
            case SADDLE: return 8;
            default: return 0;
        }
    }

    private void animation(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerEntityAnimation packet = new WrapperPlayServerEntityAnimation(event);
        WrapperPlayServerEntityAnimation.EntityAnimationType type = packet.getType();
        if (type == null) return;
        int entityId = packet.getEntityId();
        switch (type) {
            case SWING_MAIN_ARM:
                recording.append(RecordEncoder.entityAnimation(time, entityId, ANIMATION_SWING_MAIN_HAND), time);
                return;
            case SWING_OFF_HAND:
                recording.append(RecordEncoder.entityAnimation(time, entityId, ANIMATION_SWING_OFF_HAND), time);
                return;
            case CRITICAL_HIT:
                recording.append(RecordEncoder.entityAnimation(time, entityId, ANIMATION_CRITICAL_HIT), time);
                return;
            case MAGIC_CRITICAL_HIT:
                recording.append(RecordEncoder.entityAnimation(time, entityId, ANIMATION_MAGIC_CRITICAL_HIT), time);
                return;
            case WAKE_UP:
                recording.append(RecordEncoder.entityAnimation(time, entityId, ANIMATION_WAKE_UP), time);
                return;
            case HURT:
                recording.append(RecordEncoder.entityHurt(time, entityId, null), time);
                return;
            default:
        }
    }

    private void hurt(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerHurtAnimation packet = new WrapperPlayServerHurtAnimation(event);
        recording.append(RecordEncoder.entityHurt(time, packet.getEntityId(), packet.getYaw()), time);
    }

    private void status(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerEntityStatus packet = new WrapperPlayServerEntityStatus(event);
        int status = packet.getStatus();
        if (status == STATUS_DEATH) {
            recording.append(RecordEncoder.entityDeath(time, packet.getEntityId()), time);
        } else if (status == STATUS_HURT && context.getServerVersion().isOlderThan(ServerVersion.V_1_19_4)) {
            recording.append(RecordEncoder.entityHurt(time, packet.getEntityId(), null), time);
        }
    }

    private void effect(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerEntityEffect packet = new WrapperPlayServerEntityEffect(event);
        PotionType type = packet.getPotionType();
        if (type == null) return;
        ModelEffect effect = new ModelEffect(context.getNames().id(type, () -> type.getName().toString()),
                packet.getEffectAmplifier(), packet.getEffectDurationTicks(), packet.isAmbient(), !packet.isVisible());
        recording.append(RecordEncoder.entityEffect(time, packet.getEntityId(), effect), time);
    }

    private void removeEffect(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerRemoveEntityEffect packet = new WrapperPlayServerRemoveEntityEffect(event);
        PotionType type = packet.getPotionType();
        if (type == null) return;
        recording.append(RecordEncoder.entityEffectRemove(time, packet.getEntityId(),
                context.getNames().id(type, () -> type.getName().toString())), time);
    }

    private void passengers(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerSetPassengers packet = new WrapperPlayServerSetPassengers(event);
        recording.append(RecordEncoder.entityPassengers(time, packet.getEntityId(), packet.getPassengers()), time);
    }

    private void collect(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerCollectItem packet = new WrapperPlayServerCollectItem(event);
        recording.forgetType(packet.getCollectedEntityId());
        recording.append(RecordEncoder.itemPickup(time, packet.getCollectedEntityId(), packet.getCollectorEntityId(),
                Math.max(0, packet.getPickupItemCount())), time);
    }

    private void breakProgress(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerBlockBreakAnimation packet = new WrapperPlayServerBlockBreakAnimation(event);
        Vector3i position = packet.getBlockPosition();
        int stage = packet.getDestroyStage();
        recording.append(RecordEncoder.blockBreakProgress(time, packet.getEntityId(), position.getX(), position.getY(),
                position.getZ(), (stage > 9 || stage < 0) ? -1 : stage), time);
    }
}
