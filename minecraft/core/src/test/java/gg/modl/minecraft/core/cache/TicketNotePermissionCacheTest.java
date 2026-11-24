package gg.modl.minecraft.core.cache;

import gg.modl.minecraft.core.util.Permissions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketNotePermissionCacheTest {
    private final Cache cache = new Cache(new CachedProfileRegistry());
    private final UUID staff = UUID.randomUUID();

    @Test
    void onlyTheEffectivePermissionsTheBackendSentAreGranted() {
        cache.cacheStaffPermissions(staff, "Helper", "staff-1", "Helper",
                Arrays.asList(Permissions.TICKET_VIEW_ALL, Permissions.TICKET_REPLY_ALL, Permissions.TICKET_MANAGE));

        assertFalse(cache.hasPermission(staff, Permissions.TICKET_VIEW_ALL_NOTES));
        assertFalse(cache.hasPermission(staff, Permissions.TICKET_REPLY_ALL_NOTES));
        assertFalse(cache.hasPermission(staff, Permissions.TICKET_MANAGE_TAGS));
        assertTrue(cache.hasPermission(staff, Permissions.TICKET_MANAGE));
    }

    @Test
    void theNotePermissionsAreGrantedWhenHeld() {
        cache.cacheStaffPermissions(staff, "Mod", "staff-2", "Moderator",
                Arrays.asList(Permissions.TICKET_VIEW_ALL_NOTES, Permissions.TICKET_REPLY_ALL_NOTES));

        assertTrue(cache.hasPermission(staff, Permissions.TICKET_VIEW_ALL_NOTES));
        assertTrue(cache.hasPermission(staff, Permissions.TICKET_REPLY_ALL_NOTES));
    }
}
