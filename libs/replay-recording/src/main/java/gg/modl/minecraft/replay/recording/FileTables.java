package gg.modl.minecraft.replay.recording;

import gg.modl.proto.modl.v1.ReplayBlockPalette;
import gg.modl.proto.modl.v1.ReplayBlockState;
import gg.modl.proto.modl.v1.ReplayFrame;
import gg.modl.proto.modl.v1.ReplayStringTable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class FileTables {
    private final NameTable names;
    private final BlockStateNames blocks;
    private final Map<Integer, Integer> stringIndex = new HashMap<>();
    private final Map<String, Integer> literalIndex = new HashMap<>();
    private final List<String> pendingStrings = new ArrayList<>();
    private final Map<Integer, Integer> paletteIndex = new HashMap<>();
    private final Map<ReplayBlockState, Integer> stateIndex = new HashMap<>();
    private final List<ReplayBlockState> pendingStates = new ArrayList<>();
    private int nextString;
    private int nextState;

    FileTables(NameTable names, BlockStateNames blocks) {
        this.names = names;
        this.blocks = blocks;
        ReplayBlockState air = BlockStateNames.air();
        stateIndex.put(air, nextState++);
        pendingStates.add(air);
        paletteIndex.put(0, 0);
    }

    int name(int nameId) {
        Integer known = stringIndex.get(nameId);
        if (known != null) return known;
        int index = literal(names.name(nameId));
        stringIndex.put(nameId, index);
        return index;
    }

    int literal(String value) {
        Integer known = literalIndex.get(value);
        if (known != null) return known;
        int index = nextString++;
        literalIndex.put(value, index);
        pendingStrings.add(value);
        return index;
    }

    int block(int globalId) {
        Integer known = paletteIndex.get(globalId);
        if (known != null) return known;
        ReplayBlockState state = blocks.state(globalId);
        Integer existing = stateIndex.get(state);
        int index;
        if (existing != null) {
            index = existing;
        } else {
            index = nextState++;
            stateIndex.put(state, index);
            pendingStates.add(state);
        }
        paletteIndex.put(globalId, index);
        return index;
    }

    int stateCount() {
        return nextState;
    }

    void flush(BoundedReplayWriter writer) throws IOException {
        if (!pendingStrings.isEmpty()) {
            writer.write(ReplayFrame.newBuilder().setStrings(ReplayStringTable.newBuilder().addAllValues(pendingStrings)).build());
            pendingStrings.clear();
        }
        if (!pendingStates.isEmpty()) {
            writer.write(ReplayFrame.newBuilder().setBlockPalette(ReplayBlockPalette.newBuilder().addAllStates(pendingStates)).build());
            pendingStates.clear();
        }
    }
}
