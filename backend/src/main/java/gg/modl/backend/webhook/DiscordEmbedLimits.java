package gg.modl.backend.webhook;

import java.util.ArrayList;
import java.util.List;

final class DiscordEmbedLimits {
    static final int TOTAL = 6000;

    private static final int TITLE = 256;
    private static final int DESCRIPTION = 4096;
    private static final int FIELD_NAME = 256;
    private static final int FIELD_VALUE = 1024;
    private static final int FIELDS = 25;
    private static final String ELLIPSIS = "...";

    private DiscordEmbedLimits() {
    }

    record Field(String name, String value, boolean inline) {
        int length() {
            return codePoints(name) + codePoints(value);
        }
    }

    record Embed(String title, String description, List<Field> fields) {
        int length() {
            return codePoints(title) + codePoints(description) + fields.stream().mapToInt(Field::length).sum();
        }
    }

    static Embed fit(String title, String description, List<Field> fields) {
        return fit(title, description, fields, TOTAL);
    }

    static Embed fit(String title, String description, List<Field> fields, int total) {
        String fittedTitle = cut(title, TITLE);
        String fittedDescription = cut(description, DESCRIPTION);
        int room = total - codePoints(fittedTitle) - codePoints(fittedDescription);
        List<Field> candidates = fields.stream()
            .filter(field -> field.value() != null && !field.value().isBlank())
            .map(field -> new Field(cut(field.name(), FIELD_NAME), cut(field.value(), FIELD_VALUE), field.inline()))
            .toList();
        List<Field> fitted = new ArrayList<>();
        for (Field field : candidates) {
            if (fitted.size() == FIELDS) {
                markTruncated(fitted);
                break;
            }
            if (field.length() <= room) {
                fitted.add(field);
                room -= field.length();
                continue;
            }
            int valueRoom = room - codePoints(field.name());
            if (valueRoom > ELLIPSIS.length()) {
                fitted.add(new Field(field.name(), cut(field.value(), valueRoom), field.inline()));
            } else {
                markTruncated(fitted);
            }
            break;
        }
        return new Embed(fittedTitle, fittedDescription, List.copyOf(fitted));
    }

    private static void markTruncated(List<Field> fitted) {
        if (fitted.isEmpty()) {
            return;
        }
        Field last = fitted.removeLast();
        fitted.add(new Field(last.name(), endWithEllipsis(last.value()), last.inline()));
    }

    private static String endWithEllipsis(String value) {
        if (value.endsWith(ELLIPSIS)) {
            return value;
        }
        int length = codePoints(value);
        return length <= ELLIPSIS.length() ? ELLIPSIS : value.substring(0, value.offsetByCodePoints(0, length - ELLIPSIS.length())) + ELLIPSIS;
    }

    static String cut(String text, int maxCodePoints) {
        if (text == null || codePoints(text) <= maxCodePoints) {
            return text;
        }
        int end = text.offsetByCodePoints(0, Math.max(0, maxCodePoints - ELLIPSIS.length()));
        return text.substring(0, end) + ELLIPSIS;
    }

    private static int codePoints(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }
}
