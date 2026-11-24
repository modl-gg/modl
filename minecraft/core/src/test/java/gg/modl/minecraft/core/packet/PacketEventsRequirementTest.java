package gg.modl.minecraft.core.packet;

import gg.modl.minecraft.core.boot.StartupFailure;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketEventsRequirementTest {
    private static final String REMEDY = "Install PacketEvents 2.14.0 or newer and restart the server.";

    @Test
    void aMissingPacketEventsFailsClosedNamingTheMinimumVersion() {
        StartupFailure failure = PacketEventsRequirement.unmetBy(null).orElseThrow(AssertionError::new);

        assertEquals("PacketEvents is not installed, but modl needs PacketEvents 2.14.0 or newer.", failure.getReason());
        assertEquals(REMEDY, failure.getRemedy());
        assertEquals(PacketEventsRequirement.LOGIN_MESSAGE_KEY, failure.getLoginMessageKey());
        assertEquals(Collections.singletonMap("version", "2.14.0"), failure.getLoginMessagePlaceholders());
    }

    @Test
    void anOutdatedPacketEventsFailsClosedTheSameWay() {
        StartupFailure failure = PacketEventsRequirement.unmetBy("2.7.0").orElseThrow(AssertionError::new);

        assertEquals("PacketEvents 2.7.0 is installed, but modl needs PacketEvents 2.14.0 or newer.", failure.getReason());
        assertEquals(REMEDY, failure.getRemedy());
        assertEquals(PacketEventsRequirement.LOGIN_MESSAGE_KEY, failure.getLoginMessageKey());
    }

    @Test
    void aSupportedPacketEventsMeetsTheRequirement() {
        assertFalse(PacketEventsRequirement.unmetBy("2.14.0").isPresent());
        assertFalse(PacketEventsRequirement.unmetBy("2.15.1+abc").isPresent());
        assertTrue(PacketEventsRequirement.unmetBy("").isPresent());
    }
}
