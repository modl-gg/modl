package gg.modl.minecraft.replay.recording;

import lombok.Value;

@Value
class ModelEffect {
    int effect;
    int amplifier;
    int durationTicks;
    boolean ambient;
    boolean hidden;

    void write(RecordWriter out) {
        out.writeVarInt(effect).writeVarInt(amplifier).writeSignedVarInt(durationTicks).writeBoolean(ambient).writeBoolean(hidden);
    }

    static ModelEffect read(RecordReader in) {
        return new ModelEffect(in.readVarInt(), in.readVarInt(), in.readSignedVarInt(), in.readBoolean(), in.readBoolean());
    }
}
