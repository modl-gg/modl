package gg.modl.minecraft.core.staff.action;

import gg.modl.minecraft.api.AbstractPlayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaffSessionRevocationTest {
    private final StaffActionFixture fixture = new StaffActionFixture();
    private final StaffModeActions staffMode = new StaffModeActions(fixture.context, () -> false);
    private final StaffSessionRevocation revocation = new StaffSessionRevocation(fixture.context, fixture.gate,
            staffMode, new VanishActions(fixture.context));

    @Test
    void staffWhoLostModerationActionsLeaveStaffModeAndVanish() {
        AbstractPlayer demoted = fixture.moderator("Demoted");
        AbstractPlayer hidden = fixture.moderator("Hidden");
        fixture.moderation.enterStaffMode(demoted.getUuid(), true);
        fixture.moderation.setVanished(hidden.getUuid(), true);
        fixture.cache.removeStaffPermissions(demoted.getUuid());
        fixture.cache.removeStaffPermissions(hidden.getUuid());

        revocation.endUnauthorizedSessions();

        assertFalse(fixture.moderation.isInStaffMode(demoted.getUuid()));
        assertFalse(fixture.moderation.isVanished(hidden.getUuid()));
    }

    @Test
    void staffWhoKeepThePermissionKeepTheirSession() {
        AbstractPlayer moderator = fixture.moderator("Mod");
        fixture.moderation.enterStaffMode(moderator.getUuid(), false);

        revocation.endUnauthorizedSessions();

        assertTrue(fixture.moderation.isInStaffMode(moderator.getUuid()));
    }
}
