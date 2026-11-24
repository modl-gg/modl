package gg.modl.minecraft.core.imports.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SoleOwnersTest {
    @Test
    void anAddressBelongsOnlyToTheOneAccountThatEverUsedIt() {
        SoleOwners owners = new SoleOwners();
        owners.used("198.51.100.7", "alice");
        owners.used("198.51.100.7", "alice");
        owners.used("198.51.100.8", "alice");
        owners.used("198.51.100.8", "bob");
        owners.used("198.51.100.8", "alice");

        assertEquals("alice", owners.soleOwner("198.51.100.7"));
        assertNull(owners.soleOwner("198.51.100.8"));
        assertNull(owners.soleOwner("203.0.113.9"));
    }
}
