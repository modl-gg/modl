package gg.modl.minecraft.features.staffmode;

import gg.modl.minecraft.features.config.StaffModeConfig;
import gg.modl.minecraft.features.freeze.FreezeCore;
import gg.modl.minecraft.features.freeze.FreezeOps;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.features.locale.FeaturesLocaleManager;
import gg.modl.minecraft.features.staffmode.custody.CustodyRecord;
import gg.modl.minecraft.features.staffmode.custody.CustodyStore;
import gg.modl.minecraft.core.network.MemberAction;
import gg.modl.minecraft.core.network.MemberActionKind;
import gg.modl.minecraft.core.network.MemberActionOutcome;
import gg.modl.minecraft.core.network.MemberActionSender;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.features.TeleportOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaffModeCoreTest {

    private final RecordingPluginLogger logger = new RecordingPluginLogger();

    @TempDir
    Path tempDir;

    private final FakeStaffModeOps ops = new FakeStaffModeOps();
    private final ImmediateFeatureScheduler scheduler = new ImmediateFeatureScheduler(ops);
    private final FeaturesLocaleManager localeManager = new FeaturesLocaleManager(new LocaleManager());

    private final RecordingActionSender actions = new RecordingActionSender();
    private final InMemoryStartingLocationStore store = new InMemoryStartingLocationStore();
    private final InMemoryCustodyStore custodyStore = new InMemoryCustodyStore();

    private StaffModeCore newCore(StaffModeConfig config) {
        FreezeCore freezeCore = new FreezeCore(localeManager, new NoOpFreezeOps());
        StaffModeCore core = new StaffModeCore(config, localeManager, logger, scheduler, freezeCore, ops, ops,
                new StaffModePersistence(store, custodyStore), actions, () -> "Lobby");
        core.start();
        return core;
    }

    @Test
    void enterStaffModeSavesSnapshotAndSetsCreative() throws IOException {
        StaffModeConfig config = config();
        StaffModeCore core = newCore(config);
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");

        core.enterStaffMode(staff);

        assertTrue(core.isInStaffMode(staff));
        assertTrue(ops.holdsBelongings(staff));
        assertEquals(1, ops.saved.size());
        assertTrue(ops.gameModes.contains(StaffGameMode.CREATIVE));
    }

    @Test
    void offlineExitRestoresSnapshotOnRejoin() throws IOException {
        StaffModeConfig config = config();
        StaffModeCore core = newCore(config);
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        core.enterStaffMode(staff);

        ops.online.remove(staff);
        core.exitStaffMode(staff);

        assertFalse(core.isInStaffMode(staff));
        assertTrue(ops.holdsBelongings(staff));
        assertTrue(ops.restored.isEmpty());

        ops.online.add(staff);
        core.handlePlayerJoin(staff);

        assertTrue(ops.restored.contains(staff));
        assertFalse(ops.holdsBelongings(staff));
    }

    @Test
    void offlineEnterAppliesSetupOnJoin() throws IOException {
        StaffModeConfig config = config();
        StaffModeCore core = newCore(config);
        UUID staff = UUID.randomUUID();
        core.enterStaffMode(staff);
        ops.connect(staff, "Staffer");

        core.handlePlayerJoin(staff);

        assertTrue(core.isInStaffMode(staff));
        assertTrue(ops.holdsBelongings(staff));
        assertTrue(ops.saved.contains(staff));
        assertTrue(ops.gameModes.contains(StaffGameMode.CREATIVE));
    }

    @Test
    void regularJoinDoesNotReapplySetupForActiveStaffWithSnapshot() throws IOException {
        StaffModeConfig config = config();
        StaffModeCore core = newCore(config);
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        core.enterStaffMode(staff);

        ops.saved.clear();
        ops.gameModes.clear();
        ops.restored.clear();

        core.handlePlayerJoin(staff);

        assertTrue(ops.saved.isEmpty());
        assertTrue(ops.gameModes.isEmpty());
        assertTrue(ops.restored.isEmpty());
    }

    @Test
    void enteringStaffModeNoLongerVanishesByItself() throws IOException {
        StaffModeCore core = newCore(config());
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");

        core.enterStaffMode(staff);

        assertFalse(core.isVanished(staff));
    }

    @Test
    void appliedVanishHidesStaffFromNonStaffObservers() throws IOException {
        StaffModeCore core = newCore(config());
        UUID staff = UUID.randomUUID();
        UUID observer = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        ops.connect(observer, "Observer");
        core.enterStaffMode(staff);

        core.applyVanish(staff, true);

        assertTrue(core.isVanished(staff));
        assertTrue(ops.hideCalls.contains(observer + "<-" + staff));
    }

    @Test
    void quitRestoresSnapshotAndClearsStaffState() throws IOException {
        StaffModeConfig config = config();
        StaffModeCore core = newCore(config);
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        core.enterStaffMode(staff);

        core.handlePlayerQuit(staff);

        assertFalse(core.isInStaffMode(staff));
        assertTrue(ops.restored.contains(staff));
        assertFalse(ops.holdsBelongings(staff));
    }

    @Test
    void exitReturnsTheStaffMemberToWhereStaffModeStarted() throws IOException {
        StaffModeConfig config = config();
        StaffModeCore core = newCore(config);
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        ops.positions.put(staff, "base");
        core.enterStaffMode(staff);
        ops.positions.put(staff, "suspect");

        core.exitStaffMode(staff);

        assertEquals(Arrays.asList("base"), ops.returnedToStart);
        assertFalse(store.saved.containsKey(staff));
    }

    @Test
    void quitKeepsTheStartingLocationUntilTheStaffMemberJoinsAgain() throws IOException {
        StaffModeConfig config = config();
        StaffModeCore core = newCore(config);
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        ops.positions.put(staff, "base");
        core.enterStaffMode(staff);
        ops.positions.put(staff, "suspect");

        core.handlePlayerQuit(staff);
        ops.online.remove(staff);

        assertTrue(ops.returnedToStart.isEmpty());
        assertTrue(store.saved.containsKey(staff));
        assertFalse(ops.holdsBelongings(staff));

        ops.online.add(staff);
        core.handlePlayerJoin(staff);

        assertEquals(Arrays.asList("base"), ops.returnedToStart);
        assertFalse(store.saved.containsKey(staff));
        assertEquals(1, ops.restored.size());
    }

    @Test
    void rejoiningInStaffModeKeepsTheOriginalStartingLocation() throws IOException {
        StaffModeConfig config = config();
        StaffModeCore core = newCore(config);
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        ops.positions.put(staff, "base");
        core.enterStaffMode(staff);
        ops.positions.put(staff, "suspect");
        core.handlePlayerQuit(staff);
        ops.online.remove(staff);
        core.enterStaffMode(staff);

        ops.online.add(staff);
        core.handlePlayerJoin(staff);

        assertTrue(core.isInStaffMode(staff));
        assertTrue(ops.returnedToStart.isEmpty());

        ops.positions.put(staff, "elsewhere");
        core.exitStaffMode(staff);

        assertEquals(Arrays.asList("base"), ops.returnedToStart);
    }

    @Test
    void shutdownKeepsTheStartingLocationsOfStaffWhoLeft() throws IOException {
        StaffModeConfig config = config();
        StaffModeCore core = newCore(config);
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        core.enterStaffMode(staff);
        core.handlePlayerQuit(staff);
        ops.online.remove(staff);

        core.shutdown();

        assertTrue(store.saved.containsKey(staff));
        assertFalse(ops.holdsBelongings(staff));
        assertTrue(logger.severe.isEmpty());
    }

    @Test
    void staffWhoLeftReturnOnTheirFirstJoinAfterARestart() throws IOException {
        StaffModeConfig config = config();
        StaffModeCore beforeRestart = newCore(config);
        UUID staff = staffInStaffModeAwayFromBase(beforeRestart);
        beforeRestart.handlePlayerQuit(staff);
        ops.online.remove(staff);
        beforeRestart.shutdown();

        StaffModeCore afterRestart = newCore(config);
        assertTrue(ops.returnedToStart.isEmpty());
        ops.online.add(staff);
        afterRestart.handlePlayerJoin(staff);

        assertEquals(Arrays.asList("base"), ops.returnedToStart);
        assertTrue(store.saved.isEmpty());
    }

    @Test
    void aCrashInStaffModeStillReturnsTheStaffMember() throws IOException {
        StaffModeConfig config = config();
        UUID staff = staffInStaffModeAwayFromBase(newCore(config));
        ops.snapshots.clear();
        ops.online.remove(staff);

        StaffModeCore afterCrash = newCore(config);
        ops.online.add(staff);
        afterCrash.handlePlayerJoin(staff);

        assertEquals(Arrays.asList("base"), ops.returnedToStart);
        assertTrue(store.saved.isEmpty());
    }

    @Test
    void aReloadReturnsOnlineStaffWhoseTeleportDidNotFinishBeforeShutdown() throws IOException {
        StaffModeConfig config = config();
        StaffModeCore beforeReload = newCore(config);
        UUID staff = staffInStaffModeAwayFromBase(beforeReload);
        ops.teleportResult = new CompletableFuture<>();
        beforeReload.shutdown();
        assertTrue(store.saved.containsKey(staff));

        ops.teleportResult = CompletableFuture.completedFuture(TeleportOutcome.MOVED);
        newCore(config);

        assertEquals(Arrays.asList("base"), ops.returnedToStart);
        assertTrue(store.saved.isEmpty());
    }

    @Test
    void aReturnRefusedDuringShutdownIsTriedAgainAfterTheReload() throws IOException {
        StaffModeConfig config = config();
        StaffModeCore beforeReload = newCore(config);
        UUID staff = staffInStaffModeAwayFromBase(beforeReload);
        ops.teleportResult = CompletableFuture.completedFuture(TeleportOutcome.REFUSED);
        beforeReload.shutdown();
        assertTrue(store.saved.containsKey(staff));

        ops.teleportResult = CompletableFuture.completedFuture(TeleportOutcome.MOVED);
        newCore(config);

        assertEquals(Arrays.asList("base"), ops.returnedToStart);
    }

    @Test
    void shutdownReturnsOnlineStaffBeforeTheLastSave() throws IOException {
        StaffModeCore core = newCore(config());
        staffInStaffModeAwayFromBase(core);

        core.shutdown();

        assertEquals(Arrays.asList("base"), ops.returnedToStart);
        assertTrue(store.saved.isEmpty());
    }

    @Test
    void enteringAgainWhileTheReturnIsStillTeleportingKeepsTheOriginalPlace() throws IOException {
        StaffModeCore core = newCore(config());
        UUID staff = staffInStaffModeAwayFromBase(core);
        CompletableFuture<TeleportOutcome> unfinished = new CompletableFuture<>();
        ops.teleportResult = unfinished;
        core.exitStaffMode(staff);

        core.enterStaffMode(staff);
        unfinished.complete(TeleportOutcome.MOVED);
        assertTrue(store.saved.containsKey(staff));
        ops.positions.put(staff, "elsewhere");
        ops.teleportResult = CompletableFuture.completedFuture(TeleportOutcome.MOVED);
        core.exitStaffMode(staff);

        assertEquals(Arrays.asList("base", "base"), ops.returnedToStart);
        assertTrue(store.saved.isEmpty());
    }

    private UUID staffInStaffModeAwayFromBase(StaffModeCore core) {
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        ops.positions.put(staff, "base");
        core.enterStaffMode(staff);
        ops.positions.put(staff, "suspect");
        return staff;
    }

    @Test
    void exitRestoresSnapshotWhenScoreboardTeardownFails() throws IOException {
        StaffModeConfig config = config();
        StaffModeCore core = newCore(config);
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        core.enterStaffMode(staff);
        ops.scoreboardRemovalFails = true;

        core.exitStaffMode(staff);

        assertFalse(core.isInStaffMode(staff));
        assertTrue(ops.restored.contains(staff));
        assertFalse(ops.holdsBelongings(staff));
    }

    @Test
    void quitRestoresSnapshotWhenScoreboardTeardownFails() throws IOException {
        StaffModeConfig config = config();
        StaffModeCore core = newCore(config);
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        core.enterStaffMode(staff);
        core.applyVanish(staff, true);
        ops.scoreboardRemovalFails = true;

        core.handlePlayerQuit(staff);

        assertTrue(ops.restored.contains(staff));
        assertFalse(core.isVanished(staff));
        assertFalse(ops.holdsBelongings(staff));
    }

    @Test
    void shutdownRestoresEveryStaffMemberWhenScoreboardTeardownFails() throws IOException {
        StaffModeConfig config = config();
        StaffModeCore core = newCore(config);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ops.connect(first, "First");
        ops.connect(second, "Second");
        core.enterStaffMode(first);
        core.enterStaffMode(second);
        ops.scoreboardRemovalFails = true;

        core.shutdown();

        assertTrue(ops.restored.contains(first));
        assertTrue(ops.restored.contains(second));
        assertFalse(core.isInStaffMode(first));
        assertFalse(core.isInStaffMode(second));
    }

    @Test
    void quitKeepsBelongingsWhenRestoreFails() throws IOException {
        StaffModeConfig config = config();
        StaffModeCore core = newCore(config);
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        core.enterStaffMode(staff);
        ops.restoreFails = true;

        core.handlePlayerQuit(staff);

        assertFalse(core.isInStaffMode(staff));
        assertTrue(ops.holdsBelongings(staff));
    }

    @Test
    void rejoinAfterFailedRestoreReturnsBelongings() throws IOException {
        StaffModeConfig config = config();
        StaffModeCore core = newCore(config);
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        core.enterStaffMode(staff);
        ops.restoreFails = true;
        core.handlePlayerQuit(staff);

        ops.restoreFails = false;
        core.handlePlayerJoin(staff);

        assertTrue(ops.restored.contains(staff));
        assertFalse(ops.holdsBelongings(staff));
    }

    @Test
    void quitRestoresBelongingsAfterEagerExitClearedTheFlag() throws IOException {
        StaffModeConfig config = config();
        StaffModeCore core = newCore(config);
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        core.enterStaffMode(staff);
        ops.scoreboardRemovalFails = true;
        ops.restoreFails = true;
        core.exitStaffMode(staff);

        ops.restoreFails = false;
        core.handlePlayerQuit(staff);

        assertTrue(ops.restored.contains(staff));
        assertFalse(ops.holdsBelongings(staff));
    }

    @Test
    void shutdownRestoresCustodyHolderThatIsNoLongerFlagged() throws IOException {
        StaffModeConfig config = config();
        StaffModeCore core = newCore(config);
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        core.enterStaffMode(staff);
        ops.restoreFails = true;
        core.exitStaffMode(staff);
        ops.restoreFails = false;

        core.shutdown();

        assertTrue(ops.restored.contains(staff));
    }

    @Test
    void enterDoesNotClearInventoryWhenSnapshotCannotBeTaken() throws IOException {
        StaffModeConfig config = config();
        StaffModeCore core = newCore(config);
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        ops.saveFails = true;

        core.enterStaffMode(staff);

        assertTrue(ops.clearedInventories.isEmpty());
        assertFalse(core.isInStaffMode(staff));
    }

    @Test
    void aRefusedStaffModeTellsTheStaffMember() throws IOException {
        StaffModeCore core = newCore(config());
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        ops.saveFails = true;

        core.enterStaffMode(staff);

        assertEquals(Arrays.asList(localeManager.getMessage("staff_mode.custody.not_saved")), ops.messages.get(staff));
        assertNotEquals("staff_mode.custody.not_saved", ops.messages.get(staff).get(0));
    }

    @Test
    void aRefusedStaffModeIsReportedToTheCoreOnce() throws IOException {
        StaffModeCore core = newCore(config());
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        ops.saveFails = true;

        core.enterStaffMode(staff);

        assertEquals(1, refusalsReported(staff));
        assertEquals(1, ops.messages.get(staff).size());
        assertFalse(core.hasUnreportedRefusal(staff));
        assertFalse(core.sessionPlayers().contains(staff));
    }

    @Test
    void anUnansweredRefusalIsReportedAgainInsteadOfRetryingStaffMode() throws IOException {
        StaffModeCore core = newCore(config());
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        ops.saveFails = true;
        actions.outcome = MemberActionOutcome.NO_CORE;

        core.enterStaffMode(staff);
        ops.saveFails = false;
        core.enterStaffMode(staff);

        assertEquals(2, refusalsReported(staff));
        assertFalse(core.isInStaffMode(staff));
        assertTrue(core.hasUnreportedRefusal(staff));
        assertTrue(core.sessionPlayers().contains(staff));

        actions.outcome = MemberActionOutcome.ACCEPTED;
        core.exitStaffMode(staff);
        assertFalse(core.hasUnreportedRefusal(staff));
        assertEquals(2, refusalsReported(staff));
    }

    @Test
    void aRefusalIsForgottenWhenTheStaffMemberLeaves() throws IOException {
        StaffModeCore core = newCore(config());
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        ops.saveFails = true;
        actions.outcome = MemberActionOutcome.NO_CORE;

        core.enterStaffMode(staff);
        core.handlePlayerQuit(staff);

        assertFalse(core.hasUnreportedRefusal(staff));
    }

    private long refusalsReported(UUID staff) {
        return actions.sent.stream()
                .filter(action -> action.getKind() == MemberActionKind.STAFF_MODE_REFUSED && staff.equals(action.getActor()))
                .count();
    }

    @Test
    void nothingIsClearedOrTeleportedWhileTheBelongingsAreBeingWritten() throws IOException {
        CompletableFuture<Void> write = new CompletableFuture<>();
        StaffModeCore core = coreWritingThrough(write);
        UUID staff = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        ops.connect(target, "Target");

        core.enterStaffMode(staff);
        core.setTarget(staff, target);
        core.applyVanish(staff, true);
        core.handleHotbarAction(staff, 8);

        assertTrue(ops.clearedInventories.isEmpty());
        assertTrue(ops.gameModes.isEmpty());
        assertTrue(ops.teleports.isEmpty());
        assertTrue(actions.sent.isEmpty());

        write.complete(null);

        assertTrue(core.hasStaffInventory(staff));
        assertEquals(1, ops.teleports.size());
    }

    @Test
    void shutdownWithAFailedReturnKeepsTheCustodyForTheNextJoin() throws IOException {
        StaffModeConfig config = config();
        StaffModeCore beforeRestart = newCore(config);
        UUID staff = staffInStaffModeAwayFromBase(beforeRestart);
        ops.restoreFails = true;
        beforeRestart.shutdown();
        assertFalse(custodyStore.records.get(staff).isReleased());
        ops.online.remove(staff);
        ops.restoreFails = false;

        StaffModeCore afterRestart = newCore(config);
        ops.online.add(staff);
        afterRestart.handlePlayerJoin(staff);

        assertTrue(ops.restored.contains(staff));
        assertEquals(Arrays.asList("base"), ops.returnedToStart);
    }

    private StaffModeCore coreWritingThrough(CompletableFuture<Void> write) throws IOException {
        CustodyStore slowStore = new CustodyStore() {
            @Override
            public CompletableFuture<Optional<CustodyRecord>> load(UUID player) {
                return custodyStore.load(player);
            }

            @Override
            public CompletableFuture<Void> save(UUID player, CustodyRecord record) {
                return write;
            }

            @Override
            public CompletableFuture<Void> remove(UUID player) {
                return custodyStore.remove(player);
            }

            @Override
            public CompletableFuture<String> setAside(UUID player) {
                return custodyStore.setAside(player);
            }

            @Override
            public String describe(UUID player) {
                return custodyStore.describe(player);
            }

            @Override
            public void close() {
            }
        };
        StaffModeCore core = new StaffModeCore(config(), localeManager, logger, scheduler,
                new FreezeCore(localeManager, new NoOpFreezeOps()), ops, ops,
                new StaffModePersistence(store, slowStore), actions, () -> "Lobby");
        core.start();
        return core;
    }

    @Test
    void offlineExitLeavesStaffModeAndKeepsVanishSeparate() throws IOException {
        StaffModeCore core = newCore(config());
        UUID staff = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        core.enterStaffMode(staff);
        core.applyVanish(staff, true);
        core.setTarget(staff, target);
        ops.online.remove(staff);

        core.exitStaffMode(staff);

        assertFalse(core.isInStaffMode(staff));
        assertNull(core.targetOf(staff));
        assertTrue(core.isVanished(staff));
    }

    @Test
    void shutdownReportsBelongingsItCouldNotReturn() throws IOException {
        StaffModeConfig config = config();
        StaffModeCore core = newCore(config);
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        core.enterStaffMode(staff);
        ops.restoreFails = true;
        core.handlePlayerQuit(staff);
        ops.restoreFails = false;
        ops.online.remove(staff);

        core.shutdown();

        assertFalse(ops.restored.contains(staff));
        assertEquals(1, logger.severe.size());
        assertTrue(logger.severe.get(0).contains(staff.toString()));
    }

    @Test
    void scoreboardResolvesPlaceholders() throws IOException {
        StaffModeConfig config = config(
                "staff_scoreboard:",
                "  enabled: true",
                "  title: \"&bStaff {server}\"",
                "  lines:",
                "    - \"Players: {online}\"",
                "    - \"Me: {player_name}\""
        );
        StaffModeCore core = newCore(config);
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");

        core.enterStaffMode(staff);

        ScoreboardContent content = ops.scoreboards.get(staff);
        assertNotNull(content);
        assertTrue(content.getTitle().contains("Lobby"));
        assertEquals("Players: 1", content.getLines().get(0).getText());
        assertEquals("Me: Staffer", content.getLines().get(1).getText());
        assertEquals(2, content.getLines().get(0).getScore());
        assertEquals(1, content.getLines().get(1).getScore());
    }

    @Test
    void scoreboardTruncatesLongLinesToFortyChars() throws IOException {
        String longLine = repeat("ABCDEFGHIJ", 5);
        StaffModeConfig config = config(
                "staff_scoreboard:",
                "  enabled: true",
                "  title: \"Staff\"",
                "  lines:",
                "    - \"" + longLine + "\""
        );
        StaffModeCore core = newCore(config);
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");

        core.enterStaffMode(staff);

        List<ScoreboardContent.Line> lines = ops.scoreboards.get(staff).getLines();
        assertEquals(40, lines.get(0).getText().length());
        assertEquals(longLine.substring(0, 40), lines.get(0).getText());
    }

    @Test
    void scoreboardDeduplicatesIdenticalLongLines() throws IOException {
        String longLine = repeat("ABCDEFGHIJ", 5);
        StaffModeConfig config = config(
                "staff_scoreboard:",
                "  enabled: true",
                "  title: \"Staff\"",
                "  lines:",
                "    - \"" + longLine + "\"",
                "    - \"" + longLine + "\""
        );
        StaffModeCore core = newCore(config);
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");

        core.enterStaffMode(staff);

        List<ScoreboardContent.Line> lines = ops.scoreboards.get(staff).getLines();
        assertEquals(2, lines.size());
        assertNotEquals(lines.get(0).getText(), lines.get(1).getText());
    }

    @Test
    void hotbarFreezeTargetGoesToTheSenderWithoutLocalFeedback() throws IOException {
        StaffModeCore core = newCore(config());
        UUID staff = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        ops.connect(target, "Target");
        core.enterStaffMode(staff);
        core.setTarget(staff, target);

        core.handleHotbarAction(staff, 0);

        assertEquals(1, actions.sent.size());
        assertEquals(MemberActionKind.TOGGLE_FREEZE_TARGET, actions.sent.get(0).getKind());
        assertEquals(staff, actions.sent.get(0).getActor());
        assertFalse(ops.messages.containsKey(staff));
    }

    @Test
    void hotbarVanishToggleGoesToTheSenderAndDoesNotVanishLocally() throws IOException {
        StaffModeCore core = newCore(config());
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        core.enterStaffMode(staff);

        core.handleHotbarAction(staff, 3);

        assertEquals(MemberActionKind.TOGGLE_VANISH, actions.sent.get(0).getKind());
        assertFalse(core.isVanished(staff));
    }

    @Test
    void hotbarStaffMenuAndStopTargetGoToTheSender() throws IOException {
        StaffModeCore core = newCore(config());
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        core.enterStaffMode(staff);

        core.handleHotbarAction(staff, 8);
        core.setTarget(staff, UUID.randomUUID());
        core.handleHotbarAction(staff, 3);

        assertEquals(MemberActionKind.OPEN_STAFF_MENU, actions.sent.get(0).getKind());
        assertEquals(MemberActionKind.CLEAR_TARGET, actions.sent.get(1).getKind());
        assertNotNull(core.targetOf(staff));
    }

    @Test
    void targetSelectSendsTheClickedPlayerAndLeavesTheTargetToTheCore() throws IOException {
        StaffModeCore core = newCore(config());
        UUID staff = UUID.randomUUID();
        UUID clicked = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        ops.connect(clicked, "Suspect");
        core.enterStaffMode(staff);

        core.handleTargetSelect(staff, 0, clicked);

        assertEquals(MemberActionKind.SELECT_TARGET, actions.sent.get(0).getKind());
        assertEquals(clicked, actions.sent.get(0).getTarget());
        assertNull(core.targetOf(staff));
    }

    @Test
    void anUnreachableCoreTellsTheActorTheNetworkIsUnavailable() throws IOException {
        StaffModeCore core = newCore(config());
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        core.enterStaffMode(staff);
        String unavailable = localeManager.getMessage("network.unavailable");

        actions.outcome = MemberActionOutcome.NO_CORE;
        core.handleHotbarAction(staff, 8);
        actions.outcome = MemberActionOutcome.RATE_LIMITED;
        core.handleHotbarAction(staff, 8);

        assertEquals(Arrays.asList(unavailable, unavailable), ops.messages.get(staff));
    }

    @Test
    void outcomesTheCoreAlreadyExplainedPrintNothingLocally() throws IOException {
        StaffModeCore core = newCore(config());
        UUID staff = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        core.enterStaffMode(staff);

        for (MemberActionOutcome outcome : Arrays.asList(MemberActionOutcome.ACCEPTED, MemberActionOutcome.DENIED,
                MemberActionOutcome.INVALID, MemberActionOutcome.NOT_PERMITTED)) {
            actions.outcome = outcome;
            core.handleHotbarAction(staff, 8);
        }

        assertFalse(ops.messages.containsKey(staff));
    }

    @Test
    void openInventoryStaysLocal() throws IOException {
        StaffModeCore core = newCore(config());
        UUID staff = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        ops.connect(target, "Target");
        core.enterStaffMode(staff);
        core.setTarget(staff, target);

        core.handleHotbarAction(staff, 5);

        assertTrue(actions.sent.isEmpty());
        assertEquals(1, ops.openedInventories.size());
    }

    @Test
    void aQuittingTargetDoesNotClearOtherStaffTargets() throws IOException {
        StaffModeCore core = newCore(config());
        UUID staff = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        ops.connect(target, "Target");
        core.enterStaffMode(staff);
        core.setTarget(staff, target);

        core.handlePlayerQuit(target);

        assertEquals(target, core.targetOf(staff));
    }

    @Test
    void settingTheSameTargetAgainDoesNotTeleportAgain() throws IOException {
        StaffModeCore core = newCore(config());
        UUID staff = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        ops.connect(target, "Target");
        core.enterStaffMode(staff);

        core.setTarget(staff, target);
        core.setTarget(staff, target);

        assertEquals(1, ops.teleports.size());
    }

    @Test
    void sessionPlayersCoverStaffModeVanishAndTargets() throws IOException {
        StaffModeCore core = newCore(config());
        UUID staff = UUID.randomUUID();
        UUID vanished = UUID.randomUUID();
        ops.connect(staff, "Staffer");
        ops.connect(vanished, "Hidden");
        core.enterStaffMode(staff);
        core.applyVanish(vanished, true);

        assertEquals(new HashSet<>(Arrays.asList(staff, vanished)), core.sessionPlayers());
    }

    private StaffModeConfig config(String... lines) throws IOException {
        Files.write(tempDir.resolve("staff_mode.yml"),
                String.join(System.lineSeparator(), lines).getBytes(StandardCharsets.UTF_8));
        return StaffModeConfig.load(tempDir, logger);
    }

    private static String repeat(String value, int times) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < times; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private static final class RecordingPluginLogger implements PluginLogger {
        private final List<String> severe = new ArrayList<>();

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

    private static final class NoOpFreezeOps implements FreezeOps {
        @Override
        public void sendMessage(UUID uuid, String message) {
        }

        @Override
        public void onFrozen(UUID target) {
        }

        @Override
        public void onUnfrozen(UUID target) {
        }
    }

    private static final class RecordingActionSender implements MemberActionSender {
        private final List<MemberAction> sent = new ArrayList<>();
        private MemberActionOutcome outcome = MemberActionOutcome.ACCEPTED;

        @Override
        public void send(MemberAction action, Consumer<MemberActionOutcome> onResult) {
            sent.add(action);
            onResult.accept(outcome);
        }
    }
}
