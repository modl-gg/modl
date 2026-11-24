package gg.modl.minecraft.core.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RecentIdsTest {
    @Test
    void acceptsFirstEventIdAndRejectsDuplicate() {
        RecentIds eventIds = new RecentIds(2);

        assertTrue(eventIds.markIfNew("event-1"));
        assertFalse(eventIds.markIfNew("event-1"));
    }

    @Test
    void evictsOldestEventIdWhenCapacityIsExceeded() {
        RecentIds eventIds = new RecentIds(2);

        assertTrue(eventIds.markIfNew("event-1"));
        assertTrue(eventIds.markIfNew("event-2"));
        assertTrue(eventIds.markIfNew("event-3"));
        assertTrue(eventIds.markIfNew("event-1"));
    }

    @Test
    void doesNotSuppressBlankEventIds() {
        RecentIds eventIds = new RecentIds(2);

        assertTrue(eventIds.markIfNew(""));
        assertTrue(eventIds.markIfNew(""));
        assertTrue(eventIds.markIfNew("   "));
        assertTrue(eventIds.markIfNew("   "));
    }
}
