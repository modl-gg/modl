package gg.modl.minecraft.replay.recording;

import lombok.Getter;
import org.jspecify.annotations.Nullable;

@Getter
final class DecodedSection {
    static final int BLOCKS = 4096;
    static final int LIGHT_BYTES = 2048;
    static final int BIOMES = 64;

    private final int sectionY;
    private int @Nullable [] states;
    private byte @Nullable [] skyLight;
    private byte @Nullable [] blockLight;
    private int @Nullable [] biomes;

    DecodedSection(int sectionY, int @Nullable [] states) {
        this.sectionY = sectionY;
        this.states = states;
    }

    static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    void set(int index, int state) {
        int[] current = states;
        if (current == null) {
            if (state == 0) return;
            current = new int[BLOCKS];
            states = current;
        }
        current[index] = state;
    }

    void light(byte @Nullable [] sky, byte @Nullable [] block) {
        if (sky != null && sky.length == LIGHT_BYTES) skyLight = sky;
        if (block != null && block.length == LIGHT_BYTES) blockLight = block;
    }

    void biomes(int[] ids) {
        if (ids.length == BIOMES) biomes = ids;
    }

    boolean isAir() {
        int[] current = states;
        if (current == null) return true;
        for (int state : current) {
            if (state != 0) return false;
        }
        return true;
    }

    boolean isEmpty() {
        return isAir() && skyLight == null && blockLight == null;
    }
}
