package gg.modl.minecraft.replay.recording;

import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

@Value
class SlotItem {
    int slot;
    @Nullable ModelItem item;

    static void writeAll(RecordWriter out, List<SlotItem> entries) {
        out.writeVarInt(entries.size());
        for (SlotItem entry : entries) {
            out.writeVarInt(entry.slot);
            ModelItem.writeOptional(out, entry.item);
        }
    }

    static List<SlotItem> readAll(RecordReader in) {
        int count = in.readVarInt();
        List<SlotItem> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new SlotItem(in.readVarInt(), ModelItem.readOptional(in)));
        }
        return entries;
    }
}
