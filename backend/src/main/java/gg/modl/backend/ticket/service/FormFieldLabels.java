package gg.modl.backend.ticket.service;

import org.jspecify.annotations.Nullable;
import java.util.Locale;
import java.util.Map;

final class FormFieldLabels {
    private static final String HEADING_SEPARATOR = ":";
    private static final String CLOSING_PUNCTUATION = ".?!:;\u2026\u3002\uFF1F\uFF01\uFF1A\uFF1B";

    private FormFieldLabels() {
    }

    static String heading(String label) {
        String trimmed = label.strip();
        boolean closed = !trimmed.isEmpty() && CLOSING_PUNCTUATION.indexOf(trimmed.charAt(trimmed.length() - 1)) >= 0;
        return closed ? trimmed : trimmed + HEADING_SEPARATOR;
    }

    static String resolve(String key, @Nullable Map<String, String> fieldLabels) {
        String label = fieldLabels == null ? null : fieldLabels.get(key);
        return label != null && !label.isBlank() ? label : format(key);
    }

    private static String format(String key) {
        if (key.isBlank()) {
            return key;
        }

        String formatted = key.replace("_", " ");

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && !Character.isWhitespace(formatted.charAt(i - 1))) {
                result.append(' ');
            }
            result.append(c);
        }
        formatted = result.toString();

        String[] words = formatted.split("\\s+");
        StringBuilder titleCase = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (!word.isEmpty()) {
                titleCase.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    titleCase.append(word.substring(1).toLowerCase(Locale.ROOT));
                }
                if (i < words.length - 1) {
                    titleCase.append(" ");
                }
            }
        }
        return titleCase.toString();
    }
}
