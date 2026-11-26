package gg.modl.backend.theme.service;

import gg.modl.backend.database.StorableText;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.theme.data.CustomStylesheet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public final class CustomCssPolicy {
    public static final int MAX_BYTES = 1024 * 1024;

    private static final Pattern COMMENT = Pattern.compile("/\\*.*?(\\*/|\\z)", Pattern.DOTALL);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern EXPRESSION = Pattern.compile("expression\\s*\\(");
    private static final Pattern BEHAVIOR = Pattern.compile("(?<![\\w-])behavior\\s*:");
    private static final String IMPORT_RULE = "@import";
    private static final String STYLE_END_TAG = "</style";
    private static final String MOZ_BINDING = "-moz-binding";
    private static final List<String> SCRIPT_SCHEMES = List.of("javascript:", "vbscript:");
    private static final int MAX_HEX_DIGITS = 6;
    private static final int HEX_RADIX = 16;
    private static final int REPLACEMENT_CHARACTER = 0xFFFD;

    private CustomCssPolicy() {
    }

    public static CustomStylesheet accepted(String css) {
        Optional<CustomCssViolation> violation = violation(css);
        if (violation.isPresent()) {
            throw new ValidationException(violation.get().message(), violation.get().code());
        }
        return new CustomStylesheet(css);
    }

    public static Optional<CustomCssViolation> violation(String css) {
        if (new CustomStylesheet(css).bytes().length > MAX_BYTES) {
            return Optional.of(CustomCssViolation.TOO_LARGE);
        }
        if (!StorableText.isStorable(css)) {
            return Optional.of(CustomCssViolation.INVALID_CHARACTER);
        }
        List<String> readings = readings(css);
        if (readings.stream().anyMatch(reading -> reading.contains(IMPORT_RULE))) {
            return Optional.of(CustomCssViolation.IMPORT);
        }
        if (readings.stream().anyMatch(reading -> reading.contains(STYLE_END_TAG))) {
            return Optional.of(CustomCssViolation.MARKUP);
        }
        return readings.stream().anyMatch(CustomCssPolicy::runsScript) ? Optional.of(CustomCssViolation.SCRIPT) : Optional.empty();
    }

    private static List<String> readings(String css) {
        return List.of(normalized(css), normalized(COMMENT.matcher(css).replaceAll(" ")));
    }

    private static String normalized(String css) {
        return unescaped(css).toLowerCase(Locale.ROOT);
    }

    private static boolean runsScript(String normalized) {
        String compact = WHITESPACE.matcher(normalized).replaceAll("");
        return SCRIPT_SCHEMES.stream().anyMatch(compact::contains)
            || compact.contains(MOZ_BINDING)
            || EXPRESSION.matcher(normalized).find()
            || BEHAVIOR.matcher(normalized).find();
    }

    private static String unescaped(String css) {
        StringBuilder result = new StringBuilder(css.length());
        int index = 0;
        while (index < css.length()) {
            char current = css.charAt(index);
            if (current != '\\' || index + 1 >= css.length()) {
                result.append(current);
                index++;
                continue;
            }
            index = appendEscape(css, index + 1, result);
        }
        return result.toString();
    }

    private static int appendEscape(String css, int start, StringBuilder result) {
        int end = start;
        while (end < css.length() && end - start < MAX_HEX_DIGITS && Character.digit(css.charAt(end), HEX_RADIX) >= 0) {
            end++;
        }
        if (end == start) {
            char escaped = css.charAt(start);
            if (escaped != '\n' && escaped != '\r' && escaped != '\f') {
                result.append(escaped);
            }
            return start + 1;
        }
        result.appendCodePoint(codePoint(Integer.parseInt(css.substring(start, end), HEX_RADIX)));
        return skipEscapeTerminator(css, end);
    }

    private static int codePoint(int value) {
        boolean surrogate = value >= Character.MIN_SURROGATE && value <= Character.MAX_SURROGATE;
        return value == 0 || surrogate || value > Character.MAX_CODE_POINT ? REPLACEMENT_CHARACTER : value;
    }

    private static int skipEscapeTerminator(String css, int index) {
        if (index >= css.length()) {
            return index;
        }
        if (css.startsWith("\r\n", index)) {
            return index + 2;
        }
        return Character.isWhitespace(css.charAt(index)) ? index + 1 : index;
    }
}
