package gg.modl.minecraft.replay.recording;

import gg.modl.proto.modl.v1.ReplayDimension;
import gg.modl.proto.modl.v1.ReplayEffect;
import gg.modl.proto.modl.v1.ReplayEnchantment;
import gg.modl.proto.modl.v1.ReplayEntityFlags;
import gg.modl.proto.modl.v1.ReplayEntityMetadata;
import gg.modl.proto.modl.v1.ReplayEntitySnapshot;
import gg.modl.proto.modl.v1.ReplayEquipment;
import gg.modl.proto.modl.v1.ReplayEquipmentSlot;
import gg.modl.proto.modl.v1.ReplayGameMode;
import gg.modl.proto.modl.v1.ReplayInventorySlot;
import gg.modl.proto.modl.v1.ReplayItemStack;
import gg.modl.proto.modl.v1.ReplayPlayerInfo;
import gg.modl.proto.modl.v1.ReplayPose;
import gg.modl.proto.modl.v1.ReplayPosition;
import gg.modl.proto.modl.v1.ReplayTargetState;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ModelProtos {
    private final FileTables tables;
    private final LegacyStates legacyStates;

    ModelProtos(FileTables tables, LegacyStates legacyStates) {
        this.tables = tables;
        this.legacyStates = legacyStates;
    }

    static ReplayPosition position(long x, long y, long z) {
        return ReplayPosition.newBuilder().setX(x).setY(y).setZ(z).build();
    }

    static ReplayDimension dimension(@Nullable WorldSegment segment) {
        if (segment == null) {
            return ReplayDimension.newBuilder().setKey(WorldPackets.OVERWORLD).setWorldName(WorldPackets.OVERWORLD)
                    .setHeight(256).setHasSkyLight(true).build();
        }
        return ReplayDimension.newBuilder().setWorld(segment.getId()).setKey(segment.getKey()).setWorldName(segment.getWorldName())
                .setMinY(segment.getMinY()).setHeight(segment.getHeight()).setHasSkyLight(segment.isSkyLight()).build();
    }

    static ReplayGameMode gameMode(int mode) {
        ReplayGameMode value = ReplayGameMode.forNumber(mode);
        return value == null ? ReplayGameMode.REPLAY_GAME_MODE_UNSPECIFIED : value;
    }

    ReplayItemStack item(ModelItem item) {
        ReplayItemStack.Builder builder = ReplayItemStack.newBuilder()
                .setItem(tables.name(item.getName()))
                .setCount(item.getCount())
                .setEnchanted(item.isEnchanted())
                .setDamage(item.getDamage())
                .setMaxDamage(item.getMaxDamage());
        for (ModelItem.Enchant enchant : item.getEnchantments()) {
            builder.addEnchantments(ReplayEnchantment.newBuilder().setKey(tables.name(enchant.getName())).setLevel(enchant.getLevel()));
        }
        Integer dye = item.getDyeColor();
        if (dye != null) builder.setDyeColor(dye);
        return builder.build();
    }

    ReplayEntityMetadata metadata(MetadataValues values) {
        ReplayEntityMetadata.Builder builder = ReplayEntityMetadata.newBuilder();
        Integer flags = values.getFlags();
        if (flags != null) builder.setFlags(flags(flags));
        Integer pose = values.getPose();
        if (pose != null) {
            ReplayPose value = ReplayPose.forNumber(pose);
            builder.setPose(value == null ? ReplayPose.REPLAY_POSE_STANDING : value);
        }
        if (values.isItemPresent()) {
            ModelItem item = values.getItem();
            builder.setItem(item == null ? ReplayItemStack.getDefaultInstance() : item(item));
        }
        String customName = values.getCustomName();
        if (customName != null) builder.setCustomName(customName);
        Boolean visible = values.getNameVisible();
        if (visible != null) builder.setCustomNameVisible(visible);
        Float health = values.getHealth();
        if (health != null) builder.setHealth(health);
        return builder.build();
    }

    private static ReplayEntityFlags flags(int flags) {
        return ReplayEntityFlags.newBuilder()
                .setOnFire((flags & MetadataTranslator.FLAG_ON_FIRE) != 0)
                .setSneaking((flags & MetadataTranslator.FLAG_SNEAKING) != 0)
                .setSprinting((flags & MetadataTranslator.FLAG_SPRINTING) != 0)
                .setSwimming((flags & MetadataTranslator.FLAG_SWIMMING) != 0)
                .setInvisible((flags & MetadataTranslator.FLAG_INVISIBLE) != 0)
                .setGlowing((flags & MetadataTranslator.FLAG_GLOWING) != 0)
                .setGliding((flags & MetadataTranslator.FLAG_GLIDING) != 0)
                .build();
    }

    ReplayEquipment equipment(int slot, @Nullable ModelItem item) {
        ReplayEquipmentSlot value = ReplayEquipmentSlot.forNumber(slot);
        ReplayEquipment.Builder builder = ReplayEquipment.newBuilder()
                .setSlot(value == null ? ReplayEquipmentSlot.REPLAY_EQUIPMENT_SLOT_UNSPECIFIED : value);
        if (item != null) builder.setItem(item(item));
        return builder.build();
    }

    ReplayEffect effect(ModelEffect effect) {
        return ReplayEffect.newBuilder().setEffect(tables.name(effect.getEffect())).setAmplifier(effect.getAmplifier())
                .setDurationTicks(effect.getDurationTicks()).setAmbient(effect.isAmbient()).setHidden(effect.isHidden()).build();
    }

    ReplayEntitySnapshot entity(ModelEntity entity) {
        ReplayEntitySnapshot.Builder builder = ReplayEntitySnapshot.newBuilder()
                .setEntityId(entity.getEntityId())
                .setType(tables.name(entity.getType()))
                .setPosition(position(entity.getX(), entity.getY(), entity.getZ()))
                .setYaw(entity.getYaw())
                .setPitch(entity.getPitch())
                .setHeadYaw(entity.getHeadYaw())
                .setOnGround(entity.isOnGround())
                .setSpawnData(entity.getData());
        Integer vehicle = entity.getVehicle();
        if (vehicle != null) builder.setVehicle(vehicle);
        UUID uuid = entity.getUuid();
        if (uuid != null) builder.setUuid(uuid.toString());
        ReplayEntityMetadata metadata = metadata(entity.getMetadata());
        Integer fallingState = legacyStates.fallingBlockState(entity);
        if (fallingState != null) metadata = metadata.toBuilder().setBlockState(tables.block(fallingState)).build();
        builder.setMetadata(metadata);
        for (Map.Entry<Integer, @Nullable ModelItem> slot : entity.getEquipment().entrySet()) {
            builder.addEquipment(equipment(slot.getKey(), slot.getValue()));
        }
        for (ModelEffect effect : entity.getEffects().values()) builder.addEffects(effect(effect));
        return builder.build();
    }

    static ReplayPlayerInfo player(ModelPlayer player) {
        return ReplayPlayerInfo.newBuilder()
                .setUuid(player.getUuid().toString())
                .setName(player.getName())
                .setSkinHash(player.getSkinHash())
                .setSlim(player.isSlim())
                .setGameMode(gameMode(player.getGameMode()))
                .setListed(player.isListed())
                .setDisplayName(player.getDisplayName())
                .setLatencyMs(player.getLatency())
                .build();
    }

    ReplayTargetState target(ModelTarget target, @Nullable ModelEntity self) {
        ReplayTargetState.Builder builder = ReplayTargetState.newBuilder()
                .setHealth(target.getHealth())
                .setMaxHealth(target.getMaxHealth())
                .setFood(target.getFood())
                .setSaturation(target.getSaturation())
                .setArmor(target.getArmor())
                .setXpLevel(target.getXpLevel())
                .setXpProgress(target.getXpProgress())
                .setXpTotal(target.getXpTotal())
                .setSelectedSlot(target.getSelectedSlot())
                .setGameMode(gameMode(target.getGameMode()));
        if (self != null) {
            Float absorption = self.getMetadata().getAbsorption();
            if (absorption != null) builder.setAbsorption(absorption);
        }
        for (int slot = 0; slot < ModelTarget.INVENTORY_SLOTS; slot++) {
            ModelItem item = target.slot(slot);
            if (item != null) builder.addInventory(ReplayInventorySlot.newBuilder().setSlot(slot).setItem(item(item)));
        }
        return builder.build();
    }

    ReplayInventorySlot slot(SlotItem slot) {
        ReplayInventorySlot.Builder builder = ReplayInventorySlot.newBuilder().setSlot(slot.getSlot());
        ModelItem item = slot.getItem();
        if (item != null) builder.setItem(item(item));
        return builder.build();
    }

    List<ReplayEquipment> equipmentList(List<SlotItem> slots, List<ReplayEquipment> into) {
        for (SlotItem slot : slots) into.add(equipment(slot.getSlot(), slot.getItem()));
        return into;
    }
}
