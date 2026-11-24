package gg.modl.minecraft.core.util;

import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import gg.modl.minecraft.core.impl.menus.util.MenuItems;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import gg.modl.minecraft.core.support.TestPluginServices;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DateFormatterTest {
    private static final Instant EPOCH = Instant.EPOCH;

    private final RecordingPluginLogger logger = new RecordingPluginLogger();

    @Test
    void aConfiguredPatternAndZoneFormatTheInstant() {
        DateFormatter formatter = DateFormatter.configured("yyyy-MM-dd HH:mm z", "UTC", logger);

        assertEquals("1970-01-01 00:00 UTC", formatter.format(EPOCH));
        assertTrue(logger.warnings().isEmpty());
    }

    @Test
    void anEmptyZoneUsesTheServerZone() {
        DateFormatter formatter = DateFormatter.configured("yyyy-MM-dd HH:mm", "", logger);

        assertEquals(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(EPOCH),
                formatter.format(EPOCH));
        assertTrue(logger.warnings().isEmpty());
    }

    @Test
    void anInvalidPatternFallsBackToTheDefaultWithOneWarning() {
        DateFormatter formatter = DateFormatter.configured("not-a-]pattern[", "UTC", logger);

        assertEquals("01/01/1970 00:00", formatter.format(EPOCH));
        assertEquals(1, logger.warnings().size());
        assertTrue(logger.warnings().get(0).contains("not-a-]pattern["), logger.warnings().get(0));
        assertTrue(logger.warnings().get(0).contains(DateFormatter.DEFAULT_PATTERN), logger.warnings().get(0));
    }

    @Test
    void anUnknownZoneFallsBackToTheServerZoneWithOneWarning() {
        DateFormatter formatter = DateFormatter.configured("yyyy-MM-dd HH:mm", "Mars/Olympus_Mons", logger);

        assertEquals(DateFormatter.configured("yyyy-MM-dd HH:mm", "", logger).format(EPOCH), formatter.format(EPOCH));
        assertEquals(1, logger.warnings().size());
        assertTrue(logger.warnings().get(0).contains("Mars/Olympus_Mons"), logger.warnings().get(0));
    }

    @Test
    void theShortZoneIdsTimeZoneAcceptedKeepWorking() {
        assertEquals("1969-12-31 19:00", DateFormatter.configured("yyyy-MM-dd HH:mm", "EST", logger).format(EPOCH));
        assertEquals("1969-12-31 16:00", DateFormatter.configured("yyyy-MM-dd HH:mm", "PST", logger).format(EPOCH));
        assertEquals("1970-01-01 05:30", DateFormatter.configured("yyyy-MM-dd HH:mm", "GMT+05:30", logger).format(EPOCH));
        assertTrue(logger.warnings().isEmpty());
    }

    @Test
    void theDefaultsUseTheDefaultPattern() {
        assertEquals(DateFormatter.configured(DateFormatter.DEFAULT_PATTERN, null, logger).format(EPOCH),
                DateFormatter.defaults().format(EPOCH));
    }

    @Test
    void menuDatesUseTheFormatterOfTheLocale() {
        LocaleManager locale = new LocaleManager();
        TestPluginServices.install(new Cache(new CachedProfileRegistry()), locale);
        DateFormatter formatter = DateFormatter.configured("yyyy-MM-dd HH:mm z", "UTC", logger);
        locale.applyDateFormat(formatter);

        assertEquals("1970-01-01 00:00 UTC", MenuItems.formatDate(EPOCH));
        assertEquals("1970-01-01 00:00 UTC", locale.formatDate(EPOCH));
        assertEquals(locale.getUnknownWord(), MenuItems.formatDate(null));
    }

    @Test
    void theFormatterUsesTheServerFormatLocale() {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
            assertEquals("Januar 1970", DateFormatter.configured("MMMM yyyy", "UTC", logger).format(EPOCH));
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous);
        }
    }
}
