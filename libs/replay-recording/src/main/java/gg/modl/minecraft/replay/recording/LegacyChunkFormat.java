package gg.modl.minecraft.replay.recording;

final class LegacyChunkFormat {
    private static final int SECTIONS = 16;
    private static final int BLOCK_BYTES = 8192;
    private static final int NIBBLE_BYTES = 2048;
    private static final int BIOME_BYTES = 256;

    private LegacyChunkFormat() {
    }

    static Bulk bulkHeader(byte[] raw) {
        boolean skyLight = raw[0] != 0;
        VarIntCursor cursor = new VarIntCursor(raw, 1);
        int count = cursor.next();
        int offset = cursor.position();
        int[] xs = new int[count];
        int[] zs = new int[count];
        int[] masks = new int[count];
        for (int i = 0; i < count; i++) {
            xs[i] = readInt(raw, offset);
            zs[i] = readInt(raw, offset + 4);
            masks[i] = ((raw[offset + 8] & 0xFF) << 8) | (raw[offset + 9] & 0xFF);
            offset += 10;
        }
        return new Bulk(skyLight, xs, zs, masks, offset);
    }

    static DecodedColumn decodeBulkEntry(byte[] raw, int index) {
        Bulk bulk = bulkHeader(raw);
        int offset = bulk.dataOffset;
        for (int i = 0; i < index; i++) offset += dataLength(bulk.masks[i], bulk.skyLight, true);
        return decode(raw, offset, bulk.xs[index], bulk.zs[index], bulk.masks[index], bulk.skyLight);
    }

    static boolean isUnload(byte[] raw) {
        return raw.length >= 11 && raw[8] != 0 && raw[9] == 0 && raw[10] == 0;
    }

    static DecodedColumn decodeSingle(byte[] raw) {
        int chunkX = readInt(raw, 0);
        int chunkZ = readInt(raw, 4);
        boolean groundUp = raw[8] != 0;
        int mask = ((raw[9] & 0xFF) << 8) | (raw[10] & 0xFF);
        VarIntCursor cursor = new VarIntCursor(raw, 11);
        int size = cursor.next();
        boolean skyLight = size == dataLength(mask, true, groundUp);
        return decode(raw, cursor.position(), chunkX, chunkZ, mask, skyLight);
    }

    private static DecodedColumn decode(byte[] raw, int offset, int chunkX, int chunkZ, int mask, boolean skyLight) {
        DecodedColumn column = new DecodedColumn(chunkX, chunkZ, 0, SECTIONS);
        int sections = Integer.bitCount(mask);
        int blockLightOffset = offset + sections * BLOCK_BYTES;
        int skyLightOffset = blockLightOffset + sections * NIBBLE_BYTES;
        int ordinal = 0;
        for (int sectionY = 0; sectionY < SECTIONS; sectionY++) {
            if ((mask & (1 << sectionY)) == 0) continue;
            int base = offset + ordinal * BLOCK_BYTES;
            int[] states = new int[DecodedSection.BLOCKS];
            for (int i = 0; i < DecodedSection.BLOCKS; i++) {
                states[i] = (raw[base + i * 2] & 0xFF) | ((raw[base + i * 2 + 1] & 0xFF) << 8);
            }
            DecodedSection section = new DecodedSection(sectionY, states);
            byte[] block = slice(raw, blockLightOffset + ordinal * NIBBLE_BYTES);
            byte[] sky = skyLight ? slice(raw, skyLightOffset + ordinal * NIBBLE_BYTES) : null;
            section.light(sky, block);
            column.put(section);
            ordinal++;
        }
        return column;
    }

    private static int dataLength(int mask, boolean skyLight, boolean groundUp) {
        int sections = Integer.bitCount(mask);
        return sections * (BLOCK_BYTES + NIBBLE_BYTES + (skyLight ? NIBBLE_BYTES : 0)) + (groundUp ? BIOME_BYTES : 0);
    }

    private static byte[] slice(byte[] raw, int offset) {
        byte[] copy = new byte[NIBBLE_BYTES];
        System.arraycopy(raw, offset, copy, 0, NIBBLE_BYTES);
        return copy;
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16) | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }

    static final class Bulk {
        private final boolean skyLight;
        private final int[] xs;
        private final int[] zs;
        private final int[] masks;
        private final int dataOffset;

        private Bulk(boolean skyLight, int[] xs, int[] zs, int[] masks, int dataOffset) {
            this.skyLight = skyLight;
            this.xs = xs;
            this.zs = zs;
            this.masks = masks;
            this.dataOffset = dataOffset;
        }

        int size() {
            return xs.length;
        }

        int chunkX(int index) {
            return xs[index];
        }

        int chunkZ(int index) {
            return zs[index];
        }
    }
}
