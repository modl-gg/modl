package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.util.adventure.AdventureSerializer;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;

final class MetadataTranslator {
    static final int FLAG_ON_FIRE = 0x01;
    static final int FLAG_SNEAKING = 0x02;
    static final int FLAG_SPRINTING = 0x08;
    static final int FLAG_SWIMMING = 0x10;
    static final int FLAG_INVISIBLE = 0x20;
    static final int FLAG_GLOWING = 0x40;
    static final int FLAG_GLIDING = 0x80;

    private static final int SHARED_FLAGS_INDEX = 0;
    private static final int CUSTOM_NAME_INDEX = 2;
    private static final int CUSTOM_NAME_VISIBLE_INDEX = 3;

    private final ItemConverter items;
    private final int flagMask;
    private final int healthIndex;

    MetadataTranslator(ItemConverter items, ServerVersion version) {
        this.items = items;
        this.flagMask = flagMask(version);
        this.healthIndex = healthIndex(version);
    }

    MetadataValues translate(List<EntityData<?>> data, boolean living, boolean player) {
        MetadataValues values = MetadataValues.EMPTY;
        for (EntityData<?> entry : data) {
            values = apply(values, entry, living, player);
        }
        return values;
    }

    private MetadataValues apply(MetadataValues values, EntityData<?> entry, boolean living, boolean player) {
        int index = entry.getIndex();
        Object value = entry.getValue();
        if (value instanceof EntityPose) return values.withPose(pose((EntityPose) value));
        if (value instanceof ItemStack) {
            if (values.isItemPresent()) return values;
            return values.withItemPresent(true).withItem(items.convert((ItemStack) value));
        }
        if (index == SHARED_FLAGS_INDEX && value instanceof Byte) return values.withFlags(((Byte) value) & flagMask);
        if (index == CUSTOM_NAME_INDEX) {
            String name = customName(value);
            return (name == null || !RecordWriter.fits(name)) ? values : values.withCustomName(name);
        }
        if (index == CUSTOM_NAME_VISIBLE_INDEX) {
            if (value instanceof Boolean) return values.withNameVisible((Boolean) value);
            if (value instanceof Byte) return values.withNameVisible(((Byte) value) != 0);
            return values;
        }
        if (value instanceof Float && living) {
            if (index == healthIndex) return values.withHealth((Float) value);
            if (player && index > healthIndex) return values.withAbsorption((Float) value);
        }
        return values;
    }

    private static @Nullable String customName(Object value) {
        if (value instanceof String) {
            String legacy = (String) value;
            return legacy.isEmpty() ? "" : AdventureSerializer.serializer().asJson(AdventureSerializer.serializer().fromLegacy(legacy));
        }
        if (value instanceof Optional) {
            Object inner = ((Optional<?>) value).orElse(null);
            if (inner == null) return "";
            if (inner instanceof Component) return AdventureSerializer.serializer().asJson((Component) inner);
        }
        if (value instanceof Component) return AdventureSerializer.serializer().asJson((Component) value);
        return null;
    }

    static int pose(EntityPose pose) {
        switch (pose) {
            case FALL_FLYING: return 2;
            case SLEEPING: return 3;
            case SWIMMING: return 4;
            case SPIN_ATTACK: return 5;
            case CROUCHING: return 6;
            case LONG_JUMPING: return 7;
            case DYING: return 8;
            case SITTING: return 9;
            default: return 1;
        }
    }

    private static int flagMask(ServerVersion version) {
        int mask = FLAG_ON_FIRE | FLAG_SNEAKING | FLAG_SPRINTING | FLAG_INVISIBLE;
        if (version.isNewerThanOrEquals(ServerVersion.V_1_9)) mask |= FLAG_GLOWING | FLAG_GLIDING;
        if (version.isNewerThanOrEquals(ServerVersion.V_1_13)) mask |= FLAG_SWIMMING;
        return mask;
    }

    private static int healthIndex(ServerVersion version) {
        if (version.isOlderThan(ServerVersion.V_1_10)) return 6;
        if (version.isOlderThan(ServerVersion.V_1_14)) return 7;
        if (version.isOlderThan(ServerVersion.V_1_17)) return 8;
        return 9;
    }
}
