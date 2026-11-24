package gg.modl.minecraft.core.network;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModerationSessionServiceTest {
    private final AtomicInteger changes = new AtomicInteger();
    private final ModerationSessionService service = new ModerationSessionService(() -> 42L, "epoch-1");

    ModerationSessionServiceTest() {
        service.addChangeListener(changes::incrementAndGet);
    }

    @Test
    void freezingTwiceChangesTheStateOnceAndRecordsTheStaffAndTime() {
        UUID target = UUID.randomUUID();
        UUID staff = UUID.randomUUID();

        assertEquals(ModerationChange.CHANGED, service.freeze(target, staff));
        assertEquals(ModerationChange.UNCHANGED, service.freeze(target, staff));

        assertTrue(service.isFrozen(target));
        Freeze freeze = service.snapshot().getState().getFrozen().get(target);
        assertEquals(staff, freeze.getStaff());
        assertEquals(42L, freeze.getFrozenAtMillis());
        assertEquals(1, changes.get());
        assertEquals(1, service.snapshot().getRevision());
    }

    @Test
    void aNoOpWriteNeitherBumpsTheRevisionNorNotifies() {
        UUID player = UUID.randomUUID();

        assertEquals(ModerationChange.UNCHANGED, service.unfreeze(player));
        assertEquals(ModerationChange.UNCHANGED, service.exitStaffMode(player));
        assertEquals(ModerationChange.UNCHANGED, service.setVanished(player, false));
        assertEquals(ModerationChange.UNCHANGED, service.clearTarget(player));

        assertEquals(0, service.snapshot().getRevision());
        assertEquals(0, changes.get());
    }

    @Test
    void togglesBetweenStatesWithEveryChangeCounted() {
        UUID staff = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        service.enterStaffMode(staff, false);
        service.setVanished(staff, true);
        service.setTarget(staff, target);
        service.setVanished(staff, false);

        assertTrue(service.isInStaffMode(staff));
        assertFalse(service.isVanished(staff));
        assertEquals(target, service.targetOf(staff));
        assertEquals(4, service.snapshot().getRevision());
        assertEquals(4, changes.get());
    }

    @Test
    void enteringStaffModeCanVanishAndExitingClearsVanishAndTarget() {
        UUID staff = UUID.randomUUID();
        service.enterStaffMode(staff, true);
        service.setTarget(staff, UUID.randomUUID());

        service.exitStaffMode(staff);

        assertFalse(service.isInStaffMode(staff));
        assertFalse(service.isVanished(staff));
        assertNull(service.targetOf(staff));
        assertTrue(service.snapshot().getState().getStaff().isEmpty());
    }

    @Test
    void aTargetNeedsStaffMode() {
        assertEquals(ModerationChange.NOT_IN_STAFF_MODE, service.setTarget(UUID.randomUUID(), UUID.randomUUID()));
        assertEquals(0, changes.get());
    }

    @Test
    void refusesFreezesAndSessionsBeyondTheCaps() {
        for (int i = 0; i < ModerationSessionService.MAX_FROZEN_PLAYERS; i++) {
            service.freeze(UUID.randomUUID(), UUID.randomUUID());
        }
        for (int i = 0; i < ModerationSessionService.MAX_STAFF_SESSIONS; i++) {
            service.enterStaffMode(UUID.randomUUID(), false);
        }

        assertEquals(ModerationChange.LIMIT_REACHED, service.freeze(UUID.randomUUID(), UUID.randomUUID()));
        assertEquals(ModerationChange.LIMIT_REACHED, service.enterStaffMode(UUID.randomUUID(), false));
        assertEquals(ModerationChange.LIMIT_REACHED, service.setVanished(UUID.randomUUID(), true));
        assertEquals(ModerationSessionService.MAX_FROZEN_PLAYERS, service.snapshot().getState().getFrozen().size());
        assertEquals(ModerationSessionService.MAX_STAFF_SESSIONS, service.snapshot().getState().getStaff().size());
    }

    @Test
    void aPlayerLeavingTheNetworkIsReleasedAndClearsEveryTargetPointingAtThem() {
        UUID leaver = UUID.randomUUID();
        UUID firstStaff = UUID.randomUUID();
        UUID secondStaff = UUID.randomUUID();
        UUID otherTarget = UUID.randomUUID();
        service.freeze(leaver, firstStaff);
        service.enterStaffMode(firstStaff, false);
        service.enterStaffMode(secondStaff, false);
        service.setTarget(firstStaff, leaver);
        service.setTarget(secondStaff, otherTarget);
        long revision = service.snapshot().getRevision();

        LeftNetworkOutcome outcome = service.playerLeftNetwork(leaver);

        assertTrue(outcome.isFrozen());
        assertFalse(outcome.isInStaffMode());
        assertEquals(1, outcome.getStaffWhoseTargetLeft().size());
        assertEquals(firstStaff, outcome.getStaffWhoseTargetLeft().get(0));
        assertFalse(service.isFrozen(leaver));
        assertNull(service.targetOf(firstStaff));
        assertTrue(service.isInStaffMode(firstStaff));
        assertEquals(otherTarget, service.targetOf(secondStaff));
        assertEquals(revision + 1, service.snapshot().getRevision());
    }

    @Test
    void aStaffMemberLeavingReportsTheirSession() {
        UUID staff = UUID.randomUUID();
        service.enterStaffMode(staff, true);

        LeftNetworkOutcome outcome = service.playerLeftNetwork(staff);

        assertTrue(outcome.isInStaffMode());
        assertTrue(outcome.isVanished());
        assertFalse(service.isInStaffMode(staff));
    }

    @Test
    void anUntrackedPlayerLeavingChangesNothing() {
        service.playerLeftNetwork(UUID.randomUUID());

        assertEquals(0, service.snapshot().getRevision());
        assertEquals(0, changes.get());
    }

    @Test
    void snapshotsCarryTheEpochAndStayImmutable() {
        UUID target = UUID.randomUUID();
        ModerationSnapshot before = service.snapshot();

        service.freeze(target, UUID.randomUUID());

        assertEquals("epoch-1", before.getEpoch());
        assertTrue(before.getState().getFrozen().isEmpty());
        assertEquals(1, service.snapshot().getState().getFrozen().size());
    }

    @Test
    void theDefaultEpochIsSixteenHexCharacters() {
        assertTrue(new ModerationSessionService().snapshot().getEpoch().matches("[0-9a-f]{16}"));
    }

    @Test
    void everySnapshotTakenUnderEightConcurrentWritersHasMatchingRevisionAndState() throws Exception {
        ExecutorService writers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        boolean mismatch = false;
        List<UUID> targets = new ArrayList<>();
        for (int i = 0; i < 8; i++) targets.add(UUID.randomUUID());
        for (UUID target : targets) {
            writers.execute(() -> {
                awaitQuietly(start);
                for (int round = 0; round < 500; round++) {
                    service.freeze(target, target);
                    service.unfreeze(target);
                }
            });
        }
        start.countDown();
        for (int i = 0; i < 2000; i++) {
            ModerationSnapshot snapshot = service.snapshot();
            long frozenEntries = snapshot.getState().getFrozen().size();
            if ((snapshot.getRevision() - frozenEntries) % 2 != 0) mismatch = true;
        }
        writers.shutdown();
        assertTrue(writers.awaitTermination(30, TimeUnit.SECONDS));

        assertFalse(mismatch);
        assertEquals(8 * 500 * 2, service.snapshot().getRevision());
        assertTrue(service.snapshot().getState().getFrozen().isEmpty());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
