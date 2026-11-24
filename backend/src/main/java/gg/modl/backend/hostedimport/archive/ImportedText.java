package gg.modl.backend.hostedimport.archive;

public final class ImportedText {
    private static final char REPLACEMENT = '�';

    private ImportedText() {
    }

    public static String clean(String value) {
        if (isClean(value)) {
            return value;
        }
        StringBuilder cleaned = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0) {
                continue;
            }
            if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                cleaned.append(c).append(value.charAt(++i));
            } else if (Character.isSurrogate(c)) {
                cleaned.append(REPLACEMENT);
            } else {
                cleaned.append(c);
            }
        }
        return cleaned.toString();
    }

    private static boolean isClean(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0) {
                return false;
            }
            if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                return false;
            }
        }
        return true;
    }
}
