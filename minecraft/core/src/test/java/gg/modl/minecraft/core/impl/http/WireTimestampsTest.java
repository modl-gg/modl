package gg.modl.minecraft.core.impl.http;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireTimestampsTest {
    private static final Instant MOMENT = Instant.parse("2026-04-21T01:36:48.919Z");

    @Test
    void everyFormTheServerHasSentParsesToTheSameInstant() {
        for (String text : new String[]{"2026-04-21T01:36:48.919Z", "2026-04-21T03:36:48.919+02:00",
                "2026-04-21T03:36:48.919+0200", "1776735408919", " 1776735408919 "}) {
            assertEquals(MOMENT, WireTimestamps.parse(text), text);
        }
    }

    @Test
    void anOffsetWithoutMinutesIsRead() {
        assertEquals(Instant.parse("2026-04-21T01:36:48Z"), WireTimestamps.parse("2026-04-21T03:36:48+02"));
    }

    @Test
    void aTimeWithoutAnOffsetIsNotGuessed() {
        assertNull(WireTimestamps.parse("2026-04-21 01:36:48"));
    }

    @Test
    void anAbsentValueIsNullAndAnUnreadableOneIsNotGuessed() {
        for (String absent : new String[]{null, "", "  ", "null", "NULL"}) {
            assertTrue(WireTimestamps.isAbsent(absent), String.valueOf(absent));
            assertNull(WireTimestamps.parse(absent), String.valueOf(absent));
        }
        assertFalse(WireTimestamps.isAbsent("yesterday"));
        assertNull(WireTimestamps.parse("yesterday"));
    }
}
