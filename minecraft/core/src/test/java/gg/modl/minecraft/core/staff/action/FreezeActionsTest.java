package gg.modl.minecraft.core.staff.action;

import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.core.network.ModerationSessionService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreezeActionsTest {
    private final StaffActionFixture fixture = new StaffActionFixture();
    private boolean enforced = true;
    private final FreezeActions actions = new FreezeActions(fixture.context, serverName -> enforced);
    private final AbstractPlayer staff = fixture.moderator("Mod");
    private final AbstractPlayer target = fixture.player("Target");

    @Test
    void togglingFreezesThenReleasesWithAStaffBroadcastEachTime() {
        actions.toggle(fixture.actor(staff), target);

        assertTrue(fixture.moderation.isFrozen(target.getUuid()));
        assertEquals(staff.getUuid(), fixture.moderation.snapshot().getState().getFrozen().get(target.getUuid()).getStaff());
        assertEquals("freeze.staff_notification_freeze", fixture.platform.staffBroadcasts().get(0));

        actions.toggle(fixture.actor(staff), target);

        assertFalse(fixture.moderation.isFrozen(target.getUuid()));
        assertEquals("freeze.staff_notification_unfreeze", fixture.platform.staffBroadcasts().get(1));
    }

    @Test
    void anOfflineTargetIsNotFrozen() {
        AbstractPlayer offline = new AbstractPlayer(UUID.randomUUID(), "Gone", false);

        actions.toggle(fixture.actor(staff), offline);

        assertFalse(fixture.moderation.isFrozen(offline.getUuid()));
        assertEquals("freeze.target_offline", fixture.lastMessageTo(staff));
    }

    @Test
    void aFreezeOnAServerThatIsNotConnectedYetIsKeptAndTheStaffMemberIsTold() {
        enforced = false;

        actions.toggle(fixture.actor(staff), target);

        assertTrue(fixture.moderation.isFrozen(target.getUuid()));
        assertEquals("freeze.not_enforced", fixture.lastMessageTo(staff));
    }

    @Test
    void theFreezeCapIsReportedToTheStaffMember() {
        for (int i = 0; i < ModerationSessionService.MAX_FROZEN_PLAYERS; i++) {
            fixture.moderation.freeze(UUID.randomUUID(), UUID.randomUUID());
        }

        actions.toggle(fixture.actor(staff), target);

        assertFalse(fixture.moderation.isFrozen(target.getUuid()));
        assertEquals("freeze.limit_reached", fixture.lastMessageTo(staff));
        assertTrue(fixture.platform.staffBroadcasts().isEmpty());
    }
}
