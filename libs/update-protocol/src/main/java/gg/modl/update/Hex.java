package gg.modl.update;

import org.jspecify.annotations.Nullable;

import java.util.regex.Pattern;

final class Hex {
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private Hex() {
    }

    static String encode(byte[] bytes) {
        char[] text = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            text[i * 2] = DIGITS[(bytes[i] >> 4) & 0xF];
            text[i * 2 + 1] = DIGITS[bytes[i] & 0xF];
        }
        return new String(text);
    }

    static boolean isSha256(@Nullable String text) {
        return text != null && SHA256.matcher(text).matches();
    }
}
