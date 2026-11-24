package gg.modl.minecraft.core.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketEventsVersionTest {
    @Test
    void acceptsTheMinimumAndNewerReleases() {
        assertTrue(PacketEventsVersion.isSupported("2.14.0"));
        assertTrue(PacketEventsVersion.isSupported("2.14.1+b4c2d1e"));
        assertTrue(PacketEventsVersion.isSupported("2.15.0-SNAPSHOT"));
        assertTrue(PacketEventsVersion.isSupported("3.0.0"));
    }

    @Test
    void rejectsOlderOrMissingVersions() {
        assertFalse(PacketEventsVersion.isSupported("2.13.9"));
        assertFalse(PacketEventsVersion.isSupported("2.7.0"));
        assertFalse(PacketEventsVersion.isSupported(""));
        assertFalse(PacketEventsVersion.isSupported(null));
        assertTrue(PacketEventsVersion.unsupportedReason("2.7.0").contains("2.14.0 or newer"));
    }
}
