package gg.modl.backend.auth.totp;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

public final class Base32 {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int BITS_PER_CHARACTER = 5;
    private static final int BITS_PER_BYTE = 8;
    private static final int CHARACTER_MASK = 0x1F;
    private static final int BYTE_MASK = 0xFF;

    private Base32() {
    }

    public static String encode(byte[] data) {
        StringBuilder encoded = new StringBuilder((data.length * BITS_PER_BYTE + BITS_PER_CHARACTER - 1) / BITS_PER_CHARACTER);
        int buffer = 0;
        int bufferedBits = 0;
        for (byte value : data) {
            buffer = (buffer << BITS_PER_BYTE) | (value & BYTE_MASK);
            bufferedBits += BITS_PER_BYTE;
            while (bufferedBits >= BITS_PER_CHARACTER) {
                bufferedBits -= BITS_PER_CHARACTER;
                encoded.append(ALPHABET.charAt((buffer >> bufferedBits) & CHARACTER_MASK));
            }
        }
        if (bufferedBits > 0) {
            encoded.append(ALPHABET.charAt((buffer << (BITS_PER_CHARACTER - bufferedBits)) & CHARACTER_MASK));
        }
        return encoded.toString();
    }

    public static byte[] decode(String text) {
        String normalized = text.replace("=", "").toUpperCase(Locale.ROOT);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream(normalized.length() * BITS_PER_CHARACTER / BITS_PER_BYTE);
        int buffer = 0;
        int bufferedBits = 0;
        for (int index = 0; index < normalized.length(); index++) {
            int value = ALPHABET.indexOf(normalized.charAt(index));
            if (value < 0) {
                throw new IllegalArgumentException("Not a base32 character at position " + index);
            }
            buffer = (buffer << BITS_PER_CHARACTER) | value;
            bufferedBits += BITS_PER_CHARACTER;
            if (bufferedBits >= BITS_PER_BYTE) {
                bufferedBits -= BITS_PER_BYTE;
                decoded.write((buffer >> bufferedBits) & BYTE_MASK);
            }
        }
        return decoded.toByteArray();
    }
}
