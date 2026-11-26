package gg.modl.minecraft.spigot.features;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpigotReplaysTest {
    @Test
    void mapsBukkitInventorySlotsToMenuSlots() {
        assertEquals(36, SpigotReplays.toProtocolSlot(0));
        assertEquals(44, SpigotReplays.toProtocolSlot(8));
        assertEquals(9, SpigotReplays.toProtocolSlot(9));
        assertEquals(35, SpigotReplays.toProtocolSlot(35));
        assertEquals(8, SpigotReplays.toProtocolSlot(36));
        assertEquals(5, SpigotReplays.toProtocolSlot(39));
        assertEquals(45, SpigotReplays.toProtocolSlot(40));
        assertEquals(-1, SpigotReplays.toProtocolSlot(41));
    }
}
