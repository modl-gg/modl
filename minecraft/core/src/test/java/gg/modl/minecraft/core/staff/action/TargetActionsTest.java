package gg.modl.minecraft.core.staff.action;

import gg.modl.minecraft.api.AbstractPlayer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetActionsTest {
    private final StaffActionFixture fixture = new StaffActionFixture();
    private final AtomicBoolean vanishOnEnable = new AtomicBoolean(true);
    private final StaffModeActions staffMode = new StaffModeActions(fixture.context, vanishOnEnable::get);
    private final TargetActions actions = new TargetActions(fixture.context, staffMode);
    private final AbstractPlayer staff = fixture.moderator("Mod");
    private final AbstractPlayer target = fixture.player("Target");

    @Test
    void selectingATargetEntersStaffModeWithTheConfiguredVanishAndStoresTheTarget() {
        actions.select(fixture.actor(staff), target);

        assertTrue(fixture.moderation.isInStaffMode(staff.getUuid()));
        assertTrue(fixture.moderation.isVanished(staff.getUuid()));
        assertEquals(target.getUuid(), fixture.moderation.targetOf(staff.getUuid()));
        assertEquals("target.targeting", fixture.lastMessageTo(staff));
        assertEquals("staff_mode.enabled_broadcast", fixture.platform.staffBroadcasts().get(0));
    }

    @Test
    void staffModeWithoutVanishWhenTheSettingIsOff() {
        vanishOnEnable.set(false);

        actions.select(fixture.actor(staff), target);

        assertFalse(fixture.moderation.isVanished(staff.getUuid()));
    }

    @Test
    void aStaffMemberCannotTargetThemselves() {
        actions.select(fixture.actor(staff), staff);

        assertFalse(fixture.moderation.isInStaffMode(staff.getUuid()));
        assertEquals("target.cannot_target_self", fixture.lastMessageTo(staff));
    }

    @Test
    void aTargetOnAnotherServerConnectsTheStaffMemberThere() {
        fixture.platform.onServer(target.getUuid(), "survival");

        actions.select(fixture.actor(staff), target);

        assertEquals(target.getUuid(), fixture.moderation.targetOf(staff.getUuid()));
        assertTrue(fixture.platform.sentMessages().stream().anyMatch(m -> m.message().equals("target.connecting")));
    }

    @Test
    void clearingReportsWhetherThereWasATarget() {
        assertFalse(actions.clear(fixture.actor(staff)));

        actions.select(fixture.actor(staff), target);

        assertTrue(actions.clear(fixture.actor(staff)));
        assertNull(fixture.moderation.targetOf(staff.getUuid()));
        assertTrue(fixture.moderation.isInStaffMode(staff.getUuid()));
        assertEquals("target.cleared", fixture.lastMessageTo(staff));
    }
}
