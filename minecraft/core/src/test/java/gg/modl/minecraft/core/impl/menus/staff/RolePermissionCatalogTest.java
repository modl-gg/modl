package gg.modl.minecraft.core.impl.menus.staff;

import gg.modl.minecraft.core.util.Permissions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static gg.modl.minecraft.core.impl.menus.staff.RolePermissionEditMenuTest.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RolePermissionCatalogTest {
    private final RolePermissionCatalog catalog = RolePermissionCatalog.of(Arrays.asList(
            entry(Permissions.PUNISHMENT_MODIFY, "punishment", null, false),
            entry(Permissions.PUNISHMENT_MODIFY_PARDON, "punishment", Permissions.PUNISHMENT_MODIFY, false),
            entry(Permissions.TICKET_VIEW_ALL, "ticket", null, false),
            entry(Permissions.TICKET_VIEW_ALL_NOTES, "ticket", null, false),
            entry("admin.audit.rollback", "admin", null, true),
            entry("admin.audit.rollback.bulk", "admin", "admin.audit.rollback", false)));

    @Test
    void aChildIsGrantedByItsParentLink() {
        assertTrue(catalog.hasGrantableParent(Permissions.PUNISHMENT_MODIFY_PARDON));
        assertTrue(catalog.grantedByParent(Permissions.PUNISHMENT_MODIFY_PARDON,
                Collections.singleton(Permissions.PUNISHMENT_MODIFY)));
        assertFalse(catalog.grantedByParent(Permissions.PUNISHMENT_MODIFY_PARDON, Collections.<String>emptySet()));
    }

    @Test
    void aGrandparentGrantsThroughTheChainOfParentLinks() {
        RolePermissionCatalog chained = RolePermissionCatalog.of(Arrays.asList(
                entry("a", "x", null, false),
                entry("a.b", "x", "a", false),
                entry("a.b.c", "x", "a.b", false)));

        assertTrue(chained.grantedByParent("a.b.c", Collections.singleton("a")));
        assertFalse(chained.grantedByParent("a", Collections.singleton("a.b")));
    }

    @Test
    void aParentlessPermissionIsNeverGrantedByAPrefixOfItsId() {
        assertFalse(catalog.hasGrantableParent(Permissions.TICKET_VIEW_ALL_NOTES));
        assertFalse(catalog.grantedByParent(Permissions.TICKET_VIEW_ALL_NOTES,
                new HashSet<>(Arrays.asList(Permissions.TICKET_VIEW_ALL, "ticket.view", "ticket"))));
    }

    @Test
    void superAdminOnlyPermissionsAreNeitherOfferedNorParents() {
        assertFalse(catalog.grantableIds().contains("admin.audit.rollback"));
        assertFalse(catalog.hasGrantableParent("admin.audit.rollback.bulk"));
        assertFalse(catalog.grantedByParent("admin.audit.rollback.bulk", Collections.singleton("admin.audit.rollback")));
    }

    @Test
    void thePermissionIsShownByItsCatalogNameAndFallsBackToItsId() {
        assertEquals("Name of " + Permissions.PUNISHMENT_MODIFY, catalog.nameOf(Permissions.PUNISHMENT_MODIFY));
        assertEquals("unknown.permission", catalog.nameOf("unknown.permission"));
    }
}
