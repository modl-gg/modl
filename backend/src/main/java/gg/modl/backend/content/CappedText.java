package gg.modl.backend.content;

public final class CappedText {
    private CappedText() {
    }

    public static String of(String value, int maxCodePoints) {
        return value.codePointCount(0, value.length()) > maxCodePoints
            ? value.substring(0, value.offsetByCodePoints(0, maxCodePoints))
            : value;
    }
}
