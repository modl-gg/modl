package gg.modl.minecraft.core.util;

import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SimpleDatePatternTest {
    private static final String[] PATTERNS_OWNERS_USE = {
            "MM/dd/yyyy HH:mm",
            "dd.MM.yyyy HH:mm:ss",
            "dd/MM/yy HH:mm",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd HH:mm:ss XX",
            "HH:mm X",
            "EEE, d MMM yyyy HH:mm:ss Z",
            "EEEE, MMMM d, yyyy h:mm a",
            "EEEEE MMMMM yyyyy",
            "yy/M/d H:m:s",
            "hh 'o''clock' a",
            "K:mm aa",
            "kk:mm",
            "yyyy.MM.dd G 'at' HH:mm:ss",
            "dd MMM yyyy 'um' HH:mm 'Uhr'",
            "u D F w W",
            "YYYY-'W'ww-u",
            "S SS SSS SSSS",
            "''yy''",
            "[yyyy] {MM} #dd",
            "yyyy年MM月dd日 HH:mm",
            "ddd HHH mmm sss",
            "LLLL LLL L",
    };
    private static final String[] ZONES = {"UTC", "America/New_York", "Asia/Kolkata", "Australia/Lord_Howe"};
    private static final long[] MOMENTS = {0L, 1_700_000_000_123L, 1_609_632_000_007L, 1_798_761_599_999L, 951_782_400_000L};

    @Test
    void patternsOwnersUseFormatExactlyAsSimpleDateFormatDid() {
        for (String pattern : PATTERNS_OWNERS_USE) {
            for (String zone : ZONES) {
                for (long moment : MOMENTS) {
                    assertEquals(legacy(pattern, zone, moment), translated(pattern, zone, moment),
                            pattern + " in " + zone + " at " + moment);
                }
            }
        }
    }

    @Test
    void zoneNamesFormatAsSimpleDateFormatDid() {
        for (String pattern : new String[]{"HH:mm z", "HH:mm zzzz"}) {
            for (String zone : new String[]{"UTC", "America/New_York", "Europe/London"}) {
                for (long moment : MOMENTS) {
                    assertEquals(legacy(pattern, zone, moment), translated(pattern, zone, moment),
                            pattern + " in " + zone + " at " + moment);
                }
            }
        }
    }

    @Test
    void patternsSimpleDateFormatRefusedAreRefused() {
        for (String pattern : new String[]{"not-a-]pattern[", "yyyy-MM-dd 'open", "XXXX", "bb", "yyyy c", "HH:mm:ss.n"}) {
            assertThrows(IllegalArgumentException.class, () -> new SimpleDateFormat(pattern, Locale.US), pattern);
            assertThrows(IllegalArgumentException.class, () -> SimpleDatePattern.toFormatter(pattern, Locale.US), pattern);
        }
    }

    @Test
    void textFieldsFollowTheLocale() {
        assertEquals(legacy("EEEE d MMMM yyyy", "UTC", 0L, Locale.FRANCE),
                SimpleDatePattern.toFormatter("EEEE d MMMM yyyy", Locale.FRANCE).withZone(ZoneId.of("UTC"))
                        .format(Instant.EPOCH));
    }

    private static String legacy(String pattern, String zone, long moment) {
        return legacy(pattern, zone, moment, Locale.US);
    }

    private static String legacy(String pattern, String zone, long moment, Locale locale) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, locale);
        format.setTimeZone(TimeZone.getTimeZone(zone));
        return format.format(moment);
    }

    private static String translated(String pattern, String zone, long moment) {
        return SimpleDatePattern.toFormatter(pattern, Locale.US).withZone(ZoneId.of(zone)).format(Instant.ofEpochMilli(moment));
    }
}
