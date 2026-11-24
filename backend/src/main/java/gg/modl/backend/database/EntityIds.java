package gg.modl.backend.database;

import java.security.SecureRandom;

public final class EntityIds {
    public static final int LENGTH = 16;
    public static final String ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz";

    private static final int BITS_PER_CHARACTER = 5;
    private static final int CHARACTER_MASK = 0x1f;
    private static final int RANDOM_BYTES = LENGTH * BITS_PER_CHARACTER / Byte.SIZE;
    private static final SecureRandom RANDOM = new SecureRandom();

    private EntityIds() {
    }

    public static String next() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        StringBuilder id = new StringBuilder(LENGTH);
        long buffer = 0;
        int bufferedBits = 0;
        for (byte value : bytes) {
            buffer = (buffer << Byte.SIZE) | (value & 0xff);
            bufferedBits += Byte.SIZE;
            while (bufferedBits >= BITS_PER_CHARACTER) {
                bufferedBits -= BITS_PER_CHARACTER;
                id.append(ALPHABET.charAt((int) (buffer >>> bufferedBits) & CHARACTER_MASK));
            }
        }
        return id.toString();
    }
}
