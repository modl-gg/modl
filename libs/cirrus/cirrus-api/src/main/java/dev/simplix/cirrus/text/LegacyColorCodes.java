package dev.simplix.cirrus.text;

public final class LegacyColorCodes {

    private static final char SECTION_SIGN = '§';
    private static final char AMPERSAND = '&';
    private static final String COLOR_AND_FORMAT_CODES = "0123456789abcdefklmnor";

    private LegacyColorCodes() {
    }

    public static String translateAmpersandCodes(String text) {
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length - 1; i++) {
            char code = asciiLowerCase(chars[i + 1]);
            if (chars[i] == AMPERSAND && COLOR_AND_FORMAT_CODES.indexOf(code) >= 0) {
                chars[i] = SECTION_SIGN;
                chars[i + 1] = code;
            }
        }
        return new String(chars);
    }

    private static char asciiLowerCase(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
    }
}
