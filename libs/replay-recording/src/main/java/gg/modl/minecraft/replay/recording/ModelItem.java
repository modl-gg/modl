package gg.modl.minecraft.replay.recording;

import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

@Value
class ModelItem {
    int name;
    int count;
    boolean enchanted;
    List<Enchant> enchantments;
    @Nullable Integer dyeColor;
    int damage;
    int maxDamage;

    void write(RecordWriter out) {
        out.writeVarInt(name).writeVarInt(count).writeBoolean(enchanted).writeVarInt(enchantments.size());
        for (Enchant enchant : enchantments) {
            out.writeVarInt(enchant.getName()).writeVarInt(enchant.getLevel());
        }
        Integer dye = dyeColor;
        out.writeBoolean(dye != null);
        if (dye != null) out.writeVarInt(dye);
        out.writeVarInt(damage).writeVarInt(maxDamage);
    }

    static ModelItem read(RecordReader in) {
        int name = in.readVarInt();
        int count = in.readVarInt();
        boolean enchanted = in.readBoolean();
        int enchantCount = in.readVarInt();
        Enchant[] enchants = new Enchant[enchantCount];
        for (int i = 0; i < enchantCount; i++) {
            enchants[i] = new Enchant(in.readVarInt(), in.readVarInt());
        }
        Integer dye = in.readBoolean() ? in.readVarInt() : null;
        return new ModelItem(name, count, enchanted, Arrays.asList(enchants), dye, in.readVarInt(), in.readVarInt());
    }

    static void writeOptional(RecordWriter out, @Nullable ModelItem item) {
        out.writeBoolean(item != null);
        if (item != null) item.write(out);
    }

    static @Nullable ModelItem readOptional(RecordReader in) {
        return in.readBoolean() ? read(in) : null;
    }

    @Value
    static class Enchant {
        int name;
        int level;
    }
}
