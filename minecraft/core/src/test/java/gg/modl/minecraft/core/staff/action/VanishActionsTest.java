package gg.modl.minecraft.core.staff.action;

import gg.modl.minecraft.api.AbstractPlayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanishActionsTest {
    private final StaffActionFixture fixture = new StaffActionFixture();
    private final VanishActions actions = new VanishActions(fixture.context);
    private final AbstractPlayer staff = fixture.moderator("Mod");

    @Test
    void togglingVanishesAndUnvanishesWithRepliesAndBroadcasts() {
        actions.toggle(fixture.actor(staff));

        assertTrue(fixture.moderation.isVanished(staff.getUuid()));
        assertEquals("vanish.enabled", fixture.lastMessageTo(staff));
        assertEquals("vanish.enabled_broadcast", fixture.platform.staffBroadcasts().get(0));

        actions.toggle(fixture.actor(staff));

        assertFalse(fixture.moderation.isVanished(staff.getUuid()));
        assertEquals("vanish.disabled", fixture.lastMessageTo(staff));
        assertEquals("vanish.disabled_broadcast", fixture.platform.staffBroadcasts().get(1));
    }

    @Test
    void vanishWorksInsideStaffModeWithoutLeavingIt() {
        fixture.moderation.enterStaffMode(staff.getUuid(), false);

        actions.toggle(fixture.actor(staff));
        actions.toggle(fixture.actor(staff));

        assertTrue(fixture.moderation.isInStaffMode(staff.getUuid()));
        assertFalse(fixture.moderation.isVanished(staff.getUuid()));
    }
}
