package gg.modl.minecraft.features.staffmode.custody;

import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.features.staffmode.custody.FakeWorld.PlayerState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static gg.modl.minecraft.features.staffmode.custody.FakeWorld.CREATIVE;
import static gg.modl.minecraft.features.staffmode.custody.FakeWorld.STAFF_ITEMS;
import static gg.modl.minecraft.features.staffmode.custody.FakeWorld.SURVIVAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaffCustodyTest {
    private static final String OWN_ITEMS = "diamond sword, elytra, shield in the off hand";

    private final FakeWorld world = new FakeWorld();
    private final List<CustodyNotice> refusals = new ArrayList<>();
    private final List<CustodyNotice> notices = new ArrayList<>();
    private final List<String> severe = new ArrayList<>();
    private final Set<UUID> inStaffMode = new HashSet<>();
    private final UUID staff = UUID.randomUUID();

    private StaffCustody custody;

    @BeforeEach
    void joinWithOwnBelongings() {
        PlayerState saved = new PlayerState();
        saved.items = OWN_ITEMS;
        world.playerData.put(staff, saved);
        bootServer();
        join();
    }

    @Test
    void crashBeforeTheWriteLeavesEverythingWithThePlayer() {
        requestStaffMode();

        crashAndRejoin();

        assertOwns(OWN_ITEMS);
        assertTrue(world.records.isEmpty());
        assertTrue(world.setAside.isEmpty());
    }

    @Test
    void nothingIsClearedUntilTheWriteFinished() {
        requestStaffMode();

        assertEquals(OWN_ITEMS, world.player(staff).items);
        assertEquals(SURVIVAL, world.player(staff).mode);
        assertNull(world.player(staff).mark);

        world.runDisk();

        assertEquals(STAFF_ITEMS, world.player(staff).items);
        assertEquals(CREATIVE, world.player(staff).mode);
        assertEquals(world.records.get(staff).getId(), world.player(staff).mark);
    }

    @Test
    void crashAfterTheWriteButBeforeTheClearRestoresNothingAndKeepsTheRecord() {
        requestStaffMode();
        custody.stop();
        world.runDisk();
        assertFalse(world.records.get(staff).isReleased());
        world.savePlayer(staff);

        crashAndRejoin();

        assertOwns(OWN_ITEMS);
        assertTrue(world.records.isEmpty());
        assertEquals(1, world.setAside.size());
    }

    @Test
    void crashAfterTheClearWithoutAnAutosaveKeepsTheSavedInventory() {
        enterStaffMode();

        crashAndRejoin();

        assertOwns(OWN_ITEMS);
        assertEquals(1, world.setAside.size());
    }

    @Test
    void crashAfterTheClearWithAnAutosaveRestoresEverything() {
        enterStaffMode();
        world.savePlayer(staff);

        crashAndRejoin();

        assertOwns(OWN_ITEMS);
        assertTrue(world.records.get(staff).isReleased());
    }

    @Test
    void aSecondCrashWhileRestoringStillRestoresExactlyTheBelongings() {
        enterStaffMode();
        world.savePlayer(staff);
        crashAndRejoin();

        crashAndRejoin();

        assertOwns(OWN_ITEMS);
    }

    @Test
    void crashDuringTheLoadAfterACrashStillRestores() {
        enterStaffMode();
        world.savePlayer(staff);
        world.crash();
        bootServer();
        world.connect(staff);
        custody.playerJoined(staff);

        crashAndRejoin();

        assertOwns(OWN_ITEMS);
    }

    @Test
    void crashAfterTheRestoreWithoutAnAutosaveRestoresAgain() {
        enterStaffMode();
        world.savePlayer(staff);
        exitStaffMode();
        world.runDisk();

        crashAndRejoin();

        assertOwns(OWN_ITEMS);
    }

    @Test
    void itemsGainedAfterASavedRestoreAreNeverOverwritten() {
        enterStaffMode();
        world.savePlayer(staff);
        exitStaffMode();
        world.runDisk();
        world.player(staff).items = OWN_ITEMS + ", beacon";
        world.savePlayer(staff);

        crashAndRejoin();

        assertOwns(OWN_ITEMS + ", beacon");
        assertTrue(world.records.isEmpty());
    }

    @Test
    void itemsGainedAfterARestoreSurviveLosingTheReleaseWrite() {
        enterStaffMode();
        exitStaffMode();
        world.player(staff).items = OWN_ITEMS + ", beacon";
        world.savePlayer(staff);

        crashAndRejoin();

        assertOwns(OWN_ITEMS + ", beacon");
        assertEquals(1, world.setAside.size());
    }

    @Test
    void itemsGivenAwayAfterASavedRestoreAreNotDuplicated() {
        enterStaffMode();
        exitStaffMode();
        world.runDisk();
        world.player(staff).items = "";
        world.savePlayer(staff);

        crashAndRejoin();

        assertOwns("");
    }

    @Test
    void staleStaffDataIsAnsweredWithTheNewestBelongings() {
        enterStaffMode();
        world.savePlayer(staff);
        exitStaffMode();
        world.runDisk();
        world.player(staff).items = OWN_ITEMS + ", beacon";
        enterStaffMode();

        crashAndRejoin();

        assertOwns(OWN_ITEMS + ", beacon");
    }

    @Test
    void staleStaffDataAfterACancelledSnapshotStillRestores() {
        enterStaffMode();
        world.savePlayer(staff);
        exitStaffMode();
        world.runDisk();
        requestStaffMode();
        exitStaffMode();
        world.runDisk();

        crashAndRejoin();

        assertOwns(OWN_ITEMS);
    }

    @Test
    void aFailedSecondWriteKeepsTheRecordTheStaleDataNeeds() {
        enterStaffMode();
        world.savePlayer(staff);
        exitStaffMode();
        world.runDisk();
        world.writesFail = true;
        requestStaffMode();
        world.runDisk();

        crashAndRejoin();

        assertOwns(OWN_ITEMS);
    }

    @Test
    void quitReturnsBelongingsBeforePlayerDataIsSaved() {
        enterStaffMode();

        quit();
        world.runDisk();

        assertEquals(OWN_ITEMS, world.playerData.get(staff).items);
        assertEquals(SURVIVAL, world.playerData.get(staff).mode);
        assertNull(world.playerData.get(staff).mark);
        assertTrue(world.records.get(staff).isReleased());
    }

    @Test
    void crashDuringQuitAfterTheSaveButBeforeTheReleaseWriteOverwritesNothing() {
        enterStaffMode();
        quit();

        crashAndRejoin();

        assertOwns(OWN_ITEMS);
    }

    @Test
    void crashDuringQuitBeforeTheSaveRestoresFromTheRecord() {
        enterStaffMode();
        world.savePlayer(staff);
        custody.playerLeft(staff);

        crashAndRejoin();

        assertOwns(OWN_ITEMS);
    }

    @Test
    void theRecordIsDeletedOnlyWhenSavedPlayerDataProvesTheRestore() {
        enterStaffMode();
        exitStaffMode();
        world.runDisk();
        assertTrue(world.records.containsKey(staff));

        quit();
        world.runDisk();
        join();

        assertTrue(world.records.isEmpty());
        assertTrue(world.setAside.isEmpty());
    }

    @Test
    void quittingWhileTheWriteRunsKeepsEverything() {
        requestStaffMode();

        quit();
        world.runDisk();
        join();

        assertOwns(OWN_ITEMS);
    }

    @Test
    void shutdownReturnsBelongingsAndRecordsIt() {
        enterStaffMode();

        shutDown();
        world.savePlayer(staff);

        assertEquals(OWN_ITEMS, world.playerData.get(staff).items);
        assertTrue(world.records.get(staff).isReleased());
    }

    @Test
    void crashDuringShutdownBeforePlayerDataIsSavedRestores() {
        enterStaffMode();
        world.savePlayer(staff);
        shutDown();

        crashAndRejoin();

        assertOwns(OWN_ITEMS);
    }

    @Test
    void aFailedReturnAtShutdownKeepsCustodyUntilItSucceeds() {
        enterStaffMode();
        world.giveBackFails = true;

        shutDown();
        world.savePlayer(staff);
        assertFalse(world.records.get(staff).isReleased());
        world.giveBackFails = false;
        crashAndRejoin();

        assertOwns(OWN_ITEMS);
    }

    @Test
    void aReloadAfterACleanShutdownTouchesNothingAndKeepsTheRecordForStalePlayerData() {
        enterStaffMode();
        world.savePlayer(staff);
        shutDown();
        world.player(staff).items = OWN_ITEMS + ", beacon";

        reloadWithPlayersOnline();

        assertOwns(OWN_ITEMS + ", beacon");
        assertTrue(world.records.containsKey(staff));
    }

    @Test
    void aCrashAfterAReloadStillFindsTheRecord() {
        enterStaffMode();
        world.savePlayer(staff);
        shutDown();
        reloadWithPlayersOnline();

        crashAndRejoin();

        assertOwns(OWN_ITEMS);
    }

    @Test
    void aReloadAfterAFailedReturnTriesAgain() {
        enterStaffMode();
        world.giveBackFails = true;
        shutDown();
        world.giveBackFails = false;

        reloadWithPlayersOnline();

        assertOwns(OWN_ITEMS);
    }

    @Test
    void aReloadDuringTheWriteKeepsEverything() {
        requestStaffMode();

        shutDown();
        reloadWithPlayersOnline();

        assertOwns(OWN_ITEMS);
        assertTrue(world.records.get(staff).isReleased());
    }

    @Test
    void aFailedWriteRefusesStaffMode() {
        world.writesFail = true;

        requestStaffMode();
        world.runDisk();

        assertOwns(OWN_ITEMS);
        assertEquals(Collections.singletonList(CustodyNotice.NOT_SAVED), refusals);
    }

    @Test
    void belongingsThatCannotBeSerializedRefuseStaffMode() {
        world.captureFails = true;

        requestStaffMode();
        world.runDisk();

        assertOwns(OWN_ITEMS);
        assertTrue(world.records.isEmpty());
        assertEquals(Collections.singletonList(CustodyNotice.NOT_SAVED), refusals);
    }

    @Test
    void aMarkThatCannotBeSetRefusesStaffMode() {
        world.takeAwayFailsBeforeMark = true;

        requestStaffMode();
        world.runDisk();

        assertOwns(OWN_ITEMS);
        assertEquals(Collections.singletonList(CustodyNotice.NOT_SAVED), refusals);
    }

    @Test
    void aClearThatFailsHalfwayIsRolledBack() {
        world.takeAwayFailsAfterMark = true;

        requestStaffMode();
        world.runDisk();

        assertOwns(OWN_ITEMS);
        assertEquals(Collections.singletonList(CustodyNotice.NOT_SAVED), refusals);
    }

    @Test
    void itemsGainedDuringTheWriteAreSavedBeforeTheClear() {
        requestStaffMode();
        world.player(staff).items = OWN_ITEMS + ", emerald";
        world.runDisk();

        exitStaffMode();

        assertOwns(OWN_ITEMS + ", emerald");
    }

    @Test
    void anInventoryThatNeverStopsChangingRefusesStaffModeAfterTheLimit() {
        requestStaffMode();
        for (int attempt = 0; attempt < StaffCustody.MAX_SNAPSHOT_ATTEMPTS; attempt++) {
            world.player(staff).items = "state " + attempt;
            world.disk.poll().run();
        }
        world.runDisk();

        assertOwns("state " + (StaffCustody.MAX_SNAPSHOT_ATTEMPTS - 1));
        assertEquals(Collections.singletonList(CustodyNotice.KEPT_CHANGING), refusals);
        assertTrue(world.records.get(staff).isReleased());
    }

    @Test
    void leavingStaffModeWhileTheInventoryIsClosedBeforeTheClearClearsNothing() {
        requestStaffMode();
        world.whileComparing = this::exitStaffMode;

        world.runDisk();

        assertOwns(OWN_ITEMS);
        assertTrue(world.records.get(staff).isReleased());
    }

    @Test
    void dyingDuringTheWriteClearsNothing() {
        requestStaffMode();
        world.player(staff).dead = true;

        world.runDisk();

        assertEquals(OWN_ITEMS, world.player(staff).items);
        assertFalse(custody.holdsBelongings(staff));
    }

    @Test
    void belongingsAreNotPutOntoADeadPlayer() {
        enterStaffMode();
        world.player(staff).dead = true;

        assertFalse(custody.release(staff));
        assertTrue(custody.holdsBelongings(staff));

        world.player(staff).dead = false;
        exitStaffMode();

        assertOwns(OWN_ITEMS);
    }

    @Test
    void aRecordUnreadableAfterAnUpgradeIsKeptAndLockedOutOfCreative() {
        enterStaffMode();
        world.savePlayer(staff);
        world.decodeFails = true;

        crashAndRejoin();

        assertTrue(custody.holdsBelongings(staff));
        assertEquals(SURVIVAL, world.player(staff).mode);
        assertFalse(world.records.get(staff).isReleased());
        assertEquals(1, severe.size());
        assertEquals(Collections.singletonList(CustodyNotice.HELD), notices);
    }

    @Test
    void aRecordThatBecomesReadableAgainIsRestored() {
        enterStaffMode();
        world.savePlayer(staff);
        world.decodeFails = true;
        crashAndRejoin();

        world.decodeFails = false;
        quit();
        join();

        assertOwns(OWN_ITEMS);
    }

    @Test
    void aDamagedRecordOfMarkedPlayerDataIsNeverMoved() {
        enterStaffMode();
        world.savePlayer(staff);
        world.readsFail = true;

        crashAndRejoin();

        assertTrue(world.records.containsKey(staff));
        assertTrue(custody.holdsBelongings(staff));
        assertEquals(SURVIVAL, world.player(staff).mode);
    }

    @Test
    void aStrandedPlayerCannotSnapshotOverTheirRealBelongings() {
        enterStaffMode();
        world.savePlayer(staff);
        world.decodeFails = true;
        crashAndRejoin();
        CustodyRecord kept = world.records.get(staff);

        requestStaffMode();
        world.runDisk();

        assertEquals(kept, world.records.get(staff));
        assertEquals(Collections.singletonList(CustodyNotice.UNRESOLVED), refusals);
    }

    @Test
    void aRecordThatCannotBeSetAsideBlocksStaffModeInsteadOfBeingOverwritten() {
        enterStaffMode();
        world.setAsideFails = true;
        crashAndRejoin();
        CustodyRecord kept = world.records.get(staff);

        requestStaffMode();
        world.runDisk();

        assertOwns(OWN_ITEMS);
        assertEquals(kept, world.records.get(staff));
        assertEquals(Collections.singletonList(CustodyNotice.UNRESOLVED), refusals);
    }

    @Test
    void anUnreadableMarkTouchesNothing() {
        enterStaffMode();
        world.savePlayer(staff);
        CustodyRecord kept = world.records.get(staff);
        world.markUnreadable = true;

        crashAndRejoin();
        requestStaffMode();
        world.runDisk();

        assertEquals(kept, world.records.get(staff));
        assertEquals(Collections.singletonList(CustodyNotice.UNRESOLVED), refusals);
    }

    @Test
    void aRecordOfAnotherSessionIsNotRestored() {
        enterStaffMode();
        world.savePlayer(staff);
        world.playerData.get(staff).mark = UUID.randomUUID();

        crashAndRejoin();

        assertEquals(STAFF_ITEMS, world.player(staff).items);
        assertTrue(world.records.containsKey(staff));
        assertTrue(custody.holdsBelongings(staff));
    }

    @Test
    void aMarkWithoutARecordLeavesCreative() {
        enterStaffMode();
        world.savePlayer(staff);
        world.records.clear();

        crashAndRejoin();

        assertEquals(SURVIVAL, world.player(staff).mode);
        assertNull(world.player(staff).mark);
        assertEquals(Collections.singletonList(CustodyNotice.RECORD_MISSING), notices);
    }

    @Test
    void lostPlayerDataIsRebuiltFromHeldCustody() {
        enterStaffMode();
        world.playerData.remove(staff);

        crashAndRejoin();

        assertOwns(OWN_ITEMS);
    }

    @Test
    void aCompletedRecordWrittenWithAnotherMarkIsKeptAfterAnUpgrade() {
        enterStaffMode();
        exitStaffMode();
        world.runDisk();
        quit();
        world.markKind = "attribute";

        join();

        assertTrue(world.records.isEmpty());
        assertEquals(1, world.setAside.size());
    }

    private void bootServer() {
        inStaffMode.clear();
        custody = new StaffCustody(world, world, world.scheduler(), new RecordingLogger(), new StaffModeStub());
    }

    private void join() {
        world.connect(staff);
        custody.playerJoined(staff);
        world.runDisk();
    }

    private void crashAndRejoin() {
        world.crash();
        bootServer();
        join();
    }

    private void quit() {
        inStaffMode.remove(staff);
        custody.playerLeft(staff);
        world.disconnect(staff);
    }

    private void shutDown() {
        custody.stop();
        custody.release(staff);
        custody.close();
    }

    private void reloadWithPlayersOnline() {
        bootServer();
        custody.playerFoundOnline(staff);
        world.runDisk();
    }

    private void requestStaffMode() {
        inStaffMode.add(staff);
        custody.hold(staff);
    }

    private void enterStaffMode() {
        requestStaffMode();
        world.runDisk();
        assertEquals(STAFF_ITEMS, world.player(staff).items);
    }

    private void exitStaffMode() {
        inStaffMode.remove(staff);
        custody.release(staff);
    }

    private void assertOwns(String items) {
        PlayerState state = world.player(staff);
        assertEquals(items, state.items);
        assertEquals(SURVIVAL, state.mode);
        assertNull(state.mark);
        assertFalse(custody.holdsBelongings(staff));
    }

    private final class StaffModeStub implements CustodyListener {
        @Override
        public void custodyResolved(UUID player) {
            if (inStaffMode.contains(player)) custody.hold(player);
            else custody.release(player);
        }

        @Override
        public void belongingsTaken(UUID player) {
            world.player(player).items = STAFF_ITEMS;
            world.player(player).mode = CREATIVE;
        }

        @Override
        public void staffModeRefused(UUID player, CustodyNotice notice) {
            inStaffMode.remove(player);
            custody.release(player);
            refusals.add(notice);
        }

        @Override
        public void notifyPlayer(UUID player, CustodyNotice notice) {
            notices.add(notice);
        }
    }

    private final class RecordingLogger implements PluginLogger {
        @Override
        public void info(String message) {
        }

        @Override
        public void warning(String message) {
        }

        @Override
        public void severe(String message) {
            severe.add(message);
        }
    }
}
