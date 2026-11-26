package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RecordingSeed {
    private final Recording recording;
    private final TranslationContext context;

    RecordingSeed(Recording recording, TranslationContext context) {
        this.recording = recording;
        this.context = context;
    }

    public RecordingSeed world(String key, String worldName, @Nullable User user, int entityId) {
        int minY = user == null ? 0 : user.getMinWorldHeight();
        int height = user == null ? 256 : user.getTotalWorldHeight();
        boolean fallbackSky = !WorldPackets.NETHER.equals(key) && !WorldPackets.END.equals(key);
        boolean skyLight = (user != null && user.getDimensionType() != null) ? user.getDimensionType().hasSkyLight() : fallbackSky;
        recording.enterWorld(key, worldName, minY, height, skyLight, user == null ? null : user.getDimensionType(), entityId);
        return this;
    }

    public RecordingSeed position(double x, double y, double z, float yaw, float pitch, boolean onGround) {
        long time = recording.now();
        recording.targetChunk(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4);
        recording.append(RecordEncoder.targetMove(time, Records.MOVE_POSITION | Records.MOVE_ROTATION,
                Fixed.of(x), Fixed.of(y), Fixed.of(z), yaw, pitch, onGround), time);
        return this;
    }

    public RecordingSeed status(float health, int food, float saturation, float maxHealth, int level, float progress, int total,
                                int selectedSlot, int gameMode, boolean hardcore) {
        long time = recording.now();
        recording.hardcore(hardcore);
        recording.append(RecordEncoder.targetHealth(time, health, food, saturation), time);
        recording.append(RecordEncoder.targetAttributes(time, maxHealth, null), time);
        recording.append(RecordEncoder.targetExperience(time, progress, level, total), time);
        recording.append(RecordEncoder.targetSlot(time, selectedSlot), time);
        recording.append(RecordEncoder.targetGameMode(time, gameMode), time);
        return this;
    }

    public RecordingSeed targetProfile(@Nullable UserProfile profile, int gameMode) {
        long time = recording.now();
        SkinTextures.Skin skin = SkinTextures.of(profile);
        recording.append(RecordEncoder.playerInfo(time, new PlayerInfoPatch(recording.getTargetUuid(), recording.getTargetName(),
                skin.getHash(), skin.isSlim(), gameMode, true, 0, "")), time);
        return this;
    }

    public RecordingSeed inventory(Map<Integer, ItemStack> menuSlots) {
        long time = recording.now();
        List<SlotItem> slots = new ArrayList<>(menuSlots.size());
        for (Map.Entry<Integer, ItemStack> entry : menuSlots.entrySet()) {
            ModelItem item = context.getItems().convert(entry.getValue());
            if (item != null) slots.add(new SlotItem(entry.getKey(), item));
        }
        recording.append(RecordEncoder.targetInventory(time, true, slots), time);
        return this;
    }

    public RecordingSeed entity(int entityId, EntityType type, @Nullable UUID uuid, double x, double y, double z, float yaw, float pitch,
                                List<EntityData<?>> metadata, Map<EquipmentSlot, ItemStack> equipment) {
        long time = recording.now();
        recording.trackType(entityId, type);
        recording.append(RecordEncoder.entitySpawn(time, entityId, context.typeName(type), uuid, Fixed.of(x), Fixed.of(y), Fixed.of(z),
                yaw, pitch, yaw, 0), time);
        MetadataValues values = context.getMetadata().translate(metadata, TranslationContext.isLiving(type), TranslationContext.isPlayer(type));
        if (!values.isEmpty()) recording.append(RecordEncoder.entityMetadata(time, entityId, values), time);
        List<SlotItem> slots = new ArrayList<>(equipment.size());
        for (Map.Entry<EquipmentSlot, ItemStack> entry : equipment.entrySet()) {
            int slot = EntityPackets.equipmentSlot(entry.getKey());
            if (slot > 0) slots.add(new SlotItem(slot, context.getItems().convert(entry.getValue())));
        }
        if (!slots.isEmpty()) recording.append(RecordEncoder.entityEquipment(time, entityId, slots), time);
        return this;
    }

    public RecordingSeed player(int entityId, UUID uuid, String name, @Nullable UserProfile profile, int gameMode,
                                double x, double y, double z, float yaw, float pitch,
                                List<EntityData<?>> metadata, Map<EquipmentSlot, ItemStack> equipment) {
        long time = recording.now();
        SkinTextures.Skin skin = SkinTextures.of(profile);
        recording.append(RecordEncoder.playerInfo(time, new PlayerInfoPatch(uuid, name, skin.getHash(), skin.isSlim(), gameMode,
                null, null, null)), time);
        return entity(entityId, EntityTypes.PLAYER, uuid, x, y, z, yaw, pitch, metadata, equipment);
    }
}
