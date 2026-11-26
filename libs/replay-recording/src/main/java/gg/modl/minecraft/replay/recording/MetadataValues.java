package gg.modl.minecraft.replay.recording;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;
import lombok.With;
import org.jspecify.annotations.Nullable;

@Value
@With(AccessLevel.PACKAGE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
class MetadataValues {
    static final MetadataValues EMPTY = new MetadataValues(null, null, false, null, null, null, null, null);

    private static final int FLAGS = 1;
    private static final int POSE = 1 << 1;
    private static final int ITEM = 1 << 2;
    private static final int CUSTOM_NAME = 1 << 3;
    private static final int NAME_VISIBLE = 1 << 4;
    private static final int HEALTH = 1 << 5;
    private static final int ABSORPTION = 1 << 6;

    @Nullable Integer flags;
    @Nullable Integer pose;
    boolean itemPresent;
    @Nullable ModelItem item;
    @Nullable String customName;
    @Nullable Boolean nameVisible;
    @Nullable Float health;
    @Nullable Float absorption;

    boolean isEmpty() {
        return flags == null && pose == null && !itemPresent && customName == null && nameVisible == null
                && health == null && absorption == null;
    }

    MetadataValues mergedWith(MetadataValues update) {
        return new MetadataValues(
                pick(update.flags, flags),
                pick(update.pose, pose),
                update.itemPresent || itemPresent,
                update.itemPresent ? update.item : item,
                pick(update.customName, customName),
                pick(update.nameVisible, nameVisible),
                pick(update.health, health),
                pick(update.absorption, absorption));
    }

    void write(RecordWriter out) {
        int mask = (flags != null ? FLAGS : 0) | (pose != null ? POSE : 0) | (itemPresent ? ITEM : 0)
                | (customName != null ? CUSTOM_NAME : 0) | (nameVisible != null ? NAME_VISIBLE : 0)
                | (health != null ? HEALTH : 0) | (absorption != null ? ABSORPTION : 0);
        out.writeVarInt(mask);
        Integer currentFlags = flags;
        if (currentFlags != null) out.writeVarInt(currentFlags);
        Integer currentPose = pose;
        if (currentPose != null) out.writeVarInt(currentPose);
        if (itemPresent) ModelItem.writeOptional(out, item);
        String currentName = customName;
        if (currentName != null) out.writeString(currentName);
        Boolean visible = nameVisible;
        if (visible != null) out.writeBoolean(visible);
        Float currentHealth = health;
        if (currentHealth != null) out.writeFloat(currentHealth);
        Float currentAbsorption = absorption;
        if (currentAbsorption != null) out.writeFloat(currentAbsorption);
    }

    static MetadataValues read(RecordReader in) {
        int mask = in.readVarInt();
        Integer flags = (mask & FLAGS) != 0 ? in.readVarInt() : null;
        Integer pose = (mask & POSE) != 0 ? in.readVarInt() : null;
        boolean itemPresent = (mask & ITEM) != 0;
        ModelItem item = itemPresent ? ModelItem.readOptional(in) : null;
        String customName = (mask & CUSTOM_NAME) != 0 ? in.readString() : null;
        Boolean nameVisible = (mask & NAME_VISIBLE) != 0 ? in.readBoolean() : null;
        Float health = (mask & HEALTH) != 0 ? in.readFloat() : null;
        Float absorption = (mask & ABSORPTION) != 0 ? in.readFloat() : null;
        return new MetadataValues(flags, pose, itemPresent, item, customName, nameVisible, health, absorption);
    }

    private static <T> @Nullable T pick(@Nullable T preferred, @Nullable T fallback) {
        return preferred != null ? preferred : fallback;
    }
}
