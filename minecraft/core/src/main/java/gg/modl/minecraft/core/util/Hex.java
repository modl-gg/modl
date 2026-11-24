package gg.modl.minecraft.core.util;

public final class Hex {
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() {
    }

    public static String encode(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            chars[i * 2] = DIGITS[(bytes[i] >> 4) & 0xF];
            chars[i * 2 + 1] = DIGITS[bytes[i] & 0xF];
        }
        return new String(chars);
    }

    public static byte[] decode(String hex) {
        if (hex.length() % 2 != 0) throw new IllegalArgumentException("Odd-length hex string");
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) ((digit(hex.charAt(i * 2)) << 4) | digit(hex.charAt(i * 2 + 1)));
        }
        return bytes;
    }

    private static int digit(char character) {
        int digit = Character.digit(character, 16);
        if (digit < 0) throw new IllegalArgumentException("Invalid hex digit " + character);
        return digit;
    }
}
