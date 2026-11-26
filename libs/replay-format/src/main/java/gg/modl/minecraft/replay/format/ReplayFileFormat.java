package gg.modl.minecraft.replay.format;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class ReplayFileFormat {
    public static final int FORMAT_VERSION = 5;
    public static final String FILE_EXTENSION = ".modlreplay";

    private static final byte[] MAGIC = "MODL".getBytes(StandardCharsets.US_ASCII);

    private ReplayFileFormat() {
    }

    public static byte[] magic() {
        return MAGIC.clone();
    }

    public static byte[] versionBytes() {
        return ByteBuffer.allocate(2).putShort((short) FORMAT_VERSION).array();
    }

    public static int prefixLength() {
        return MAGIC.length + 2;
    }

    static boolean hasMagic(byte[] prefix) {
        if (prefix.length < MAGIC.length) return false;
        for (int i = 0; i < MAGIC.length; i++) {
            if (prefix[i] != MAGIC[i]) return false;
        }
        return true;
    }
}
