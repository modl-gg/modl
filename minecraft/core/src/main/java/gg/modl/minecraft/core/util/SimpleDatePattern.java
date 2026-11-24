package gg.modl.minecraft.core.util;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.SignStyle;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalField;
import java.time.temporal.WeekFields;
import java.util.Locale;

public final class SimpleDatePattern {
    private static final char QUOTE = '\'';
    private static final int MAX_NUMBER_WIDTH = 19;
    private static final int TWO_DIGITS = 2;
    private static final int FULL_TEXT_LETTERS = 4;
    private static final int SHORT_TEXT_LETTERS = 3;
    private static final int REDUCED_YEAR_BASE = 2000;

    private final String pattern;
    private final Locale locale;
    private final WeekFields weeks;
    private final DateTimeFormatterBuilder builder = new DateTimeFormatterBuilder();
    private int position;

    private SimpleDatePattern(String pattern, Locale locale) {
        this.pattern = pattern;
        this.locale = locale;
        this.weeks = WeekFields.of(locale);
    }

    public static DateTimeFormatter toFormatter(String pattern, Locale locale) {
        return new SimpleDatePattern(pattern, locale).translate();
    }

    private DateTimeFormatter translate() {
        while (position < pattern.length()) {
            char next = pattern.charAt(position);
            if (next == QUOTE) {
                appendQuoted();
            } else if (isAsciiLetter(next)) {
                appendField(next, runLength(next));
            } else {
                builder.appendLiteral(next);
                position++;
            }
        }
        return builder.toFormatter(locale);
    }

    private void appendQuoted() {
        if (position + 1 < pattern.length() && pattern.charAt(position + 1) == QUOTE) {
            builder.appendLiteral(QUOTE);
            position += 2;
            return;
        }
        StringBuilder literal = new StringBuilder();
        position++;
        while (true) {
            if (position >= pattern.length()) throw invalid("an unterminated quote");
            char next = pattern.charAt(position);
            if (next == QUOTE) {
                if (position + 1 < pattern.length() && pattern.charAt(position + 1) == QUOTE) {
                    literal.append(QUOTE);
                    position += 2;
                    continue;
                }
                position++;
                break;
            }
            literal.append(next);
            position++;
        }
        builder.appendLiteral(literal.toString());
    }

    private int runLength(char letter) {
        int start = position;
        while (position < pattern.length() && pattern.charAt(position) == letter) position++;
        return position - start;
    }

    private void appendField(char letter, int count) {
        switch (letter) {
            case 'G': builder.appendText(ChronoField.ERA, TextStyle.SHORT); break;
            case 'y': appendYear(ChronoField.YEAR_OF_ERA, count); break;
            case 'Y': appendYear(weeks.weekBasedYear(), count); break;
            case 'M': appendMonth(count, TextStyle.SHORT, TextStyle.FULL); break;
            case 'L': appendMonth(count, TextStyle.SHORT_STANDALONE, TextStyle.FULL_STANDALONE); break;
            case 'w': appendNumber(weeks.weekOfWeekBasedYear(), count); break;
            case 'W': appendNumber(weeks.weekOfMonth(), count); break;
            case 'D': appendNumber(ChronoField.DAY_OF_YEAR, count); break;
            case 'd': appendNumber(ChronoField.DAY_OF_MONTH, count); break;
            case 'F': appendNumber(ChronoField.ALIGNED_WEEK_OF_MONTH, count); break;
            case 'E': builder.appendText(ChronoField.DAY_OF_WEEK, textStyle(count)); break;
            case 'u': appendNumber(ChronoField.DAY_OF_WEEK, count); break;
            case 'a': builder.appendText(ChronoField.AMPM_OF_DAY, TextStyle.SHORT); break;
            case 'H': appendNumber(ChronoField.HOUR_OF_DAY, count); break;
            case 'k': appendNumber(ChronoField.CLOCK_HOUR_OF_DAY, count); break;
            case 'K': appendNumber(ChronoField.HOUR_OF_AMPM, count); break;
            case 'h': appendNumber(ChronoField.CLOCK_HOUR_OF_AMPM, count); break;
            case 'm': appendNumber(ChronoField.MINUTE_OF_HOUR, count); break;
            case 's': appendNumber(ChronoField.SECOND_OF_MINUTE, count); break;
            case 'S': appendNumber(ChronoField.MILLI_OF_SECOND, count); break;
            case 'z': builder.appendZoneText(textStyle(count)); break;
            case 'Z': builder.appendOffset("+HHMM", "+0000"); break;
            case 'X': appendIsoOffset(count); break;
            default: throw invalid("the unknown pattern letter '" + letter + "'");
        }
    }

    private void appendYear(TemporalField year, int count) {
        if (count == TWO_DIGITS) {
            builder.appendValueReduced(year, TWO_DIGITS, TWO_DIGITS, REDUCED_YEAR_BASE);
        } else {
            appendNumber(year, count);
        }
    }

    private void appendMonth(int count, TextStyle shortStyle, TextStyle fullStyle) {
        if (count >= FULL_TEXT_LETTERS) {
            builder.appendText(ChronoField.MONTH_OF_YEAR, fullStyle);
        } else if (count == SHORT_TEXT_LETTERS) {
            builder.appendText(ChronoField.MONTH_OF_YEAR, shortStyle);
        } else {
            appendNumber(ChronoField.MONTH_OF_YEAR, count);
        }
    }

    private void appendNumber(TemporalField field, int count) {
        if (count > MAX_NUMBER_WIDTH) throw invalid("a field wider than " + MAX_NUMBER_WIDTH + " letters");
        if (count == 1) {
            builder.appendValue(field);
        } else {
            builder.appendValue(field, count, MAX_NUMBER_WIDTH, SignStyle.NORMAL);
        }
    }

    private void appendIsoOffset(int count) {
        switch (count) {
            case 1: builder.appendOffset("+HH", "Z"); break;
            case 2: builder.appendOffset("+HHMM", "Z"); break;
            case 3: builder.appendOffset("+HH:MM", "Z"); break;
            default: throw invalid("more than three 'X' letters");
        }
    }

    private static TextStyle textStyle(int count) {
        return count >= FULL_TEXT_LETTERS ? TextStyle.FULL : TextStyle.SHORT;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private IllegalArgumentException invalid(String problem) {
        return new IllegalArgumentException("'" + pattern + "' has " + problem);
    }
}
