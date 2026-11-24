package gg.modl.minecraft.core.network;

import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.api.http.request.CreateTicketRequest;
import gg.modl.minecraft.api.http.request.PlayerLookupRequest;
import gg.modl.minecraft.api.http.response.CreateTicketResponse;
import gg.modl.minecraft.api.http.response.PlayerProfileResponse;
import gg.modl.minecraft.core.AsyncCommandExecutor;
import gg.modl.minecraft.core.HttpClientHolder;
import gg.modl.minecraft.core.TestHttpClients;
import gg.modl.minecraft.core.command.ConfiguredCommandAliases;
import gg.modl.minecraft.core.connection.PanelUrl;
import gg.modl.minecraft.core.service.TicketService;
import gg.modl.minecraft.core.staff.action.FreezeActions;
import gg.modl.minecraft.core.staff.action.HackReportActions;
import gg.modl.minecraft.core.staff.action.InspectActions;
import gg.modl.minecraft.core.staff.action.StaffActionFixture;
import gg.modl.minecraft.core.staff.action.StaffMenuActions;
import gg.modl.minecraft.core.staff.action.StaffModeActions;
import gg.modl.minecraft.core.staff.action.TargetActions;
import gg.modl.minecraft.core.staff.action.VanishActions;
import gg.modl.minecraft.core.support.FakeModlHttpClient;
import gg.modl.minecraft.core.support.FakePlatform;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import gg.modl.minecraft.core.util.Permissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemberActionExecutorTest {
    private static final String MEMBER = "survival";
    private static final String[] ROOTS = {"freeze", "vanish", "target", "staffmenu", "inspect", "hackreport", "ban"};

    private final MenulessPlatform platform = new MenulessPlatform();
    private final StaffActionFixture fixture = new StaffActionFixture(platform);
    private final RecordingHttpClient http = new RecordingHttpClient();
    private final HttpClientHolder httpClientHolder = TestHttpClients.connected(http);
    private final AsyncCommandExecutor menuExecutor = new AsyncCommandExecutor();
    private final ManualExecutor async = new ManualExecutor();
    private final AtomicLong nanos = new AtomicLong();
    private final List<String> consoleCommands = new ArrayList<>();
    private final RecordingPluginLogger logger = new RecordingPluginLogger();
    private final AbstractPlayer staff = fixture.moderator("Mod");
    private final AbstractPlayer target = fixture.player("Target");
    private MemberActionExecutor sharedExecutor;

    MemberActionExecutorTest() {
        platform.onServer(staff.getUuid(), MEMBER);
        platform.onServer(target.getUuid(), MEMBER);
        fixture.moderation.enterStaffMode(staff.getUuid(), false);
    }

    @AfterEach
    void stopMenuExecutor() {
        menuExecutor.shutdown();
    }

    @Test
    void theWorkHopsToTheAsyncExecutorBeforeAnythingRuns() {
        AtomicReference<MemberActionOutcome> outcome = new AtomicReference<>();
        MemberActionExecutor executor = executor();

        executor.submit(fromMember(MemberActionKind.TOGGLE_VANISH, null), ActionOrigin.NETWORK, outcome::set);

        assertNull(outcome.get());
        assertFalse(fixture.moderation.isVanished(staff.getUuid()));
        async.runAll();
        assertEquals(MemberActionOutcome.ACCEPTED, outcome.get());
        assertTrue(fixture.moderation.isVanished(staff.getUuid()));
    }

    @Test
    void freezeUsesTheTargetStoredOnTheCoreAndIgnoresTheTargetSentByTheMember() {
        fixture.moderation.setTarget(staff.getUuid(), target.getUuid());
        AbstractPlayer bystander = fixture.player("Bystander");
        platform.onServer(bystander.getUuid(), MEMBER);

        MemberActionOutcome outcome = run(fromMember(MemberActionKind.TOGGLE_FREEZE_TARGET, bystander.getUuid()));

        assertEquals(MemberActionOutcome.ACCEPTED, outcome);
        assertTrue(fixture.moderation.isFrozen(target.getUuid()));
        assertFalse(fixture.moderation.isFrozen(bystander.getUuid()));
    }

    @Test
    void targetedActionsWithoutAStoredTargetAreInvalid() {
        assertEquals(MemberActionOutcome.INVALID, run(fromMember(MemberActionKind.TOGGLE_FREEZE_TARGET, target.getUuid())));
        assertEquals(MemberActionOutcome.INVALID, run(fromMember(MemberActionKind.INSPECT_TARGET, null)));
        assertEquals(MemberActionOutcome.INVALID, run(fromMember(MemberActionKind.HACKREPORT_TARGET, null)));
    }

    @Test
    void selectAndClearTargetReachTheTargetService() {
        assertEquals(MemberActionOutcome.ACCEPTED, run(fromMember(MemberActionKind.SELECT_TARGET, target.getUuid())));
        assertEquals(target.getUuid(), fixture.moderation.targetOf(staff.getUuid()));

        assertEquals(MemberActionOutcome.ACCEPTED, run(fromMember(MemberActionKind.CLEAR_TARGET, null)));
        assertNull(fixture.moderation.targetOf(staff.getUuid()));
    }

    @Test
    void selectingATargetOnAnotherServerIsRefused() {
        platform.onServer(target.getUuid(), "lobby");

        assertEquals(MemberActionOutcome.INVALID, run(fromMember(MemberActionKind.SELECT_TARGET, target.getUuid())));
        assertNull(fixture.moderation.targetOf(staff.getUuid()));
    }

    @Test
    void inspectingATargetNeedsPlayerViewing() {
        fixture.moderation.setTarget(staff.getUuid(), target.getUuid());

        assertEquals(MemberActionOutcome.DENIED, run(fromMember(MemberActionKind.INSPECT_TARGET, null)));
        assertEquals(0, http.profileLookups);
    }

    @Test
    void staffMenuInspectAndHackreportReachTheirServices() {
        fixture.cache.cacheStaffPermissions(staff.getUuid(), "Mod", "staff-Mod", "Moderator",
                Arrays.asList(Permissions.MOD_ACTIONS, Permissions.PUNISHMENT_VIEW));
        fixture.moderation.setTarget(staff.getUuid(), target.getUuid());

        assertEquals(MemberActionOutcome.ACCEPTED, run(fromMember(MemberActionKind.OPEN_STAFF_MENU, null)));
        assertEquals("general.gui_unavailable", fixture.lastMessageTo(staff));
        assertEquals(MemberActionOutcome.ACCEPTED, run(fromMember(MemberActionKind.INSPECT_TARGET, null)));
        assertEquals(1, http.profileLookups);
        assertEquals(MemberActionOutcome.ACCEPTED, run(fromMember(MemberActionKind.HACKREPORT_TARGET, null)));
        assertEquals(1, http.tickets.size());
        assertEquals(target.getUuid().toString(), http.tickets.get(0).getReportedPlayerUuid());
        assertEquals(staff.getUuid().toString(), http.tickets.get(0).getCreatorUuid());
    }

    @Test
    void anOfflineActorIsDenied() {
        MemberAction action = MemberAction.playerAction(MemberActionKind.TOGGLE_VANISH, UUID.randomUUID(), null)
                .stampedBy(MEMBER, "member-1", true);

        assertEquals(MemberActionOutcome.DENIED, run(action));
    }

    @Test
    void anActorOnAnotherServerIsDeniedWithAWarningNamingBothServers() {
        platform.onServer(staff.getUuid(), "lobby");

        assertEquals(MemberActionOutcome.DENIED, run(fromMember(MemberActionKind.TOGGLE_VANISH, null)));

        assertFalse(fixture.moderation.isVanished(staff.getUuid()));
        assertEquals("network.action_unavailable", fixture.lastMessageTo(staff));
        assertTrue(loggedLines().stream().anyMatch(line -> line.contains("[network] Refused a staff action from "
                + MEMBER + ": Mod is on lobby.")));
    }

    @Test
    void theLocalOriginSkipsOnlyTheServerNameChecks() {
        platform.onServer(staff.getUuid(), "lobby");

        assertEquals(MemberActionOutcome.ACCEPTED,
                executor().execute(fromMember(MemberActionKind.TOGGLE_VANISH, null), ActionOrigin.LOCAL));
    }

    @Test
    void anActorOutsideStaffModeIsDenied() {
        fixture.moderation.exitStaffMode(staff.getUuid());

        assertEquals(MemberActionOutcome.DENIED, run(fromMember(MemberActionKind.TOGGLE_VANISH, null)));
        assertEquals("network.action_unavailable", fixture.lastMessageTo(staff));
    }

    @Test
    void aDisabledCommandRootAlsoDisablesItsHotbarAction() {
        MemberActionExecutor executor = executorWith("freeze", "target");

        assertEquals(MemberActionOutcome.DENIED,
                executor.execute(fromMember(MemberActionKind.TOGGLE_VANISH, null), ActionOrigin.NETWORK));
        assertFalse(fixture.moderation.isVanished(staff.getUuid()));
        assertEquals("network.action_unavailable", fixture.lastMessageTo(staff));
    }

    @Test
    void theGateStillChecksPermissions() {
        fixture.cache.removeStaffPermissions(staff.getUuid());

        assertEquals(MemberActionOutcome.DENIED, run(fromMember(MemberActionKind.TOGGLE_VANISH, null)));
        assertEquals("general.no_permission", fixture.lastMessageTo(staff));
    }

    @Test
    void aProxyCommandNeedsTheCapability() {
        MemberAction ungranted = MemberAction.proxyCommand("ban Steve").stampedBy(MEMBER, "member-1", false);

        assertEquals(MemberActionOutcome.NOT_PERMITTED, run(ungranted));
        assertTrue(consoleCommands.isEmpty());
    }

    @Test
    void aProxyCommandOutsideModlIsRefusedWithAWarning() {
        assertEquals(MemberActionOutcome.DENIED, run(proxyCommand("op Attacker")));

        assertTrue(consoleCommands.isEmpty());
        assertTrue(loggedLines().stream().anyMatch(line -> line.contains(
                "[network] Refused /op from " + MEMBER + " console: only modl commands run on the proxy.")));
    }

    @Test
    void aModlProxyCommandRunsThroughLampAsTheConsoleWhateverTheAliasCaseOrSlash() {
        assertEquals(MemberActionOutcome.ACCEPTED, run(proxyCommand("/TempBan Steve 1d")));

        assertEquals(1, consoleCommands.size());
        assertEquals("TempBan Steve 1d", consoleCommands.get(0));
        assertTrue(loggedLines().stream().anyMatch(line -> line.contains(
                "[network] Running /tempban for " + MEMBER + " console")));
    }

    @Test
    void malformedProxyCommandsAreInvalid() {
        StringBuilder tooLong = new StringBuilder("ban ");
        while (tooLong.length() <= MemberAction.MAX_COMMAND_LINE_LENGTH) tooLong.append('x');

        assertEquals(MemberActionOutcome.INVALID, run(proxyCommand(tooLong.toString())));
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertEquals(MemberActionOutcome.INVALID, run(proxyCommand("ban Steve\nop Attacker")));
        assertEquals(MemberActionOutcome.INVALID, run(proxyCommand("  ")));
        assertTrue(consoleCommands.isEmpty());
    }

    @Test
    void playerActionsMustNameAnActorAndCarryNoCommandLine() {
        MemberAction noActor = MemberAction.playerAction(MemberActionKind.TOGGLE_VANISH, null, null)
                .stampedBy(MEMBER, "member-1", true);
        MemberAction withCommand = new MemberAction("request", MemberActionKind.TOGGLE_VANISH, staff.getUuid(), null,
                "ban Steve", MEMBER, "member-1", true);
        MemberAction unknownKind = new MemberAction("request", null, staff.getUuid(), null, null, MEMBER,
                "member-1", true);

        assertEquals(MemberActionOutcome.INVALID, run(noActor));
        assertEquals(MemberActionOutcome.INVALID, run(withCommand));
        assertEquals(MemberActionOutcome.INVALID, run(unknownKind));
    }

    @Test
    void eachActorIsLimitedToFiveActionsPerSecond() {
        MemberActionExecutor executor = executor();
        for (int i = 0; i < MemberActionLimits.PER_ACTOR_PER_SECOND; i++) {
            assertEquals(MemberActionOutcome.ACCEPTED,
                    executor.execute(fromMember(MemberActionKind.TOGGLE_VANISH, null), ActionOrigin.NETWORK));
        }

        assertEquals(MemberActionOutcome.RATE_LIMITED,
                executor.execute(fromMember(MemberActionKind.TOGGLE_VANISH, null), ActionOrigin.NETWORK));
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertEquals(MemberActionOutcome.ACCEPTED,
                executor.execute(fromMember(MemberActionKind.TOGGLE_VANISH, null), ActionOrigin.NETWORK));
    }

    @Test
    void eachMemberIsLimitedToTwentyActionsPerSecondAcrossActors() {
        MemberActionExecutor executor = executor();
        int accepted = 0;
        for (int i = 0; i < MemberActionLimits.PER_MEMBER_PER_SECOND + 5; i++) {
            MemberAction action = MemberAction.playerAction(MemberActionKind.TOGGLE_VANISH, UUID.randomUUID(), null)
                    .stampedBy(MEMBER, "member-1", true);
            if (executor.execute(action, ActionOrigin.NETWORK) != MemberActionOutcome.RATE_LIMITED) accepted++;
        }

        assertEquals(MemberActionLimits.PER_MEMBER_PER_SECOND, accepted);
    }

    @Test
    void proxyCommandsAreLimitedToOnePerSecondPerMember() {
        MemberActionExecutor executor = executor();

        assertEquals(MemberActionOutcome.ACCEPTED, executor.execute(proxyCommand("ban Steve"), ActionOrigin.NETWORK));
        assertEquals(MemberActionOutcome.RATE_LIMITED, executor.execute(proxyCommand("ban Alex"), ActionOrigin.NETWORK));
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertEquals(MemberActionOutcome.ACCEPTED, executor.execute(proxyCommand("ban Alex"), ActionOrigin.NETWORK));
    }

    @Test
    void nothingEverGoesThroughThePlatformCommandMap() {
        fixture.moderation.setTarget(staff.getUuid(), target.getUuid());
        for (MemberActionKind kind : MemberActionKind.values()) {
            nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
            if (kind == MemberActionKind.PROXY_COMMAND) run(proxyCommand("ban Steve"));
            else run(fromMember(kind, target.getUuid()));
        }

        assertTrue(platform.dispatchedConsoleCommands().isEmpty());
        assertTrue(platform.dispatchedPlayerCommands().isEmpty());
    }

    private MemberActionOutcome run(MemberAction action) {
        AtomicReference<MemberActionOutcome> outcome = new AtomicReference<>();
        executor().submit(action, ActionOrigin.NETWORK, outcome::set);
        async.runAll();
        return outcome.get();
    }

    @Test
    void aRefusedStaffModeEndsTheCoreSessionWithoutAnyPermissionCheck() {
        fixture.moderation.enterStaffMode(target.getUuid(), false);
        MemberAction refusal = MemberAction.playerAction(MemberActionKind.STAFF_MODE_REFUSED, target.getUuid(), null)
                .stampedBy(MEMBER, "member-1", false);

        assertEquals(MemberActionOutcome.ACCEPTED, run(refusal));

        assertFalse(fixture.moderation.isInStaffMode(target.getUuid()));
        assertEquals(MemberActionOutcome.INVALID, run(MemberAction.playerAction(MemberActionKind.STAFF_MODE_REFUSED,
                target.getUuid(), null).stampedBy(MEMBER, "member-1", false)));
    }

    @Test
    void aRefusalFromAnotherServerIsIgnored() {
        platform.onServer(staff.getUuid(), "lobby");

        assertEquals(MemberActionOutcome.DENIED, run(fromMember(MemberActionKind.STAFF_MODE_REFUSED, null)));
        assertTrue(fixture.moderation.isInStaffMode(staff.getUuid()));
    }

    private MemberAction fromMember(MemberActionKind kind, UUID targetUuid) {
        return MemberAction.playerAction(kind, staff.getUuid(), targetUuid).stampedBy(MEMBER, "member-1", true);
    }

    private List<String> loggedLines() {
        List<String> lines = new ArrayList<>(logger.infos());
        lines.addAll(logger.warnings());
        return lines;
    }

    private static MemberAction proxyCommand(String line) {
        return MemberAction.proxyCommand(line).stampedBy(MEMBER, "member-1", true);
    }

    private MemberActionExecutor executor() {
        if (sharedExecutor == null) sharedExecutor = executorWith(ROOTS);
        return sharedExecutor;
    }

    private MemberActionExecutor executorWith(String... roots) {
        Map<String, String> aliases = new HashMap<>();
        for (String root : ROOTS) aliases.put(root, root);
        aliases.put("ban", "ban|tempban");
        StaffModeActions staffModeActions = new StaffModeActions(fixture.context, () -> false);
        TicketService tickets = new TicketService(fixture.cache, httpClientHolder, platform, fixture.locale);
        MemberActionExecutor executor = MemberActionExecutor.builder()
                .asyncExecutor(async)
                .platform(platform)
                .localeManager(fixture.locale)
                .moderation(fixture.moderation)
                .commandRoots(CommandRootRegistry.builder(new ConfiguredCommandAliases(aliases)).registered(roots).build())
                .gate(fixture.gate)
                .consoleCommands(consoleCommands::add)
                .logger(logger)
                .freezeActions(new FreezeActions(fixture.context, NetworkAvailability.EVERYWHERE))
                .vanishActions(new VanishActions(fixture.context))
                .targetActions(new TargetActions(fixture.context, staffModeActions))
                .staffMenuActions(new StaffMenuActions(fixture.context, menuExecutor, httpClientHolder,
                        PanelUrl.of("https://panel.example.test")))
                .inspectActions(new InspectActions(fixture.context, httpClientHolder))
                .hackReportActions(new HackReportActions(fixture.context, tickets, () -> null))
                .staffModeActions(staffModeActions)
                .nanoClock(nanos::get)
                .build();
        return executor;
    }

    private static final class MenulessPlatform extends FakePlatform {
        @Override
        public boolean areMenusAvailable() {
            return false;
        }
    }

    private static final class RecordingHttpClient extends FakeModlHttpClient {
        private int profileLookups;
        private final List<CreateTicketRequest> tickets = new ArrayList<>();

        @Override
        public CompletableFuture<PlayerProfileResponse> lookupPlayerProfile(PlayerLookupRequest request) {
            profileLookups++;
            return new CompletableFuture<>();
        }

        @Override
        public CompletableFuture<PlayerProfileResponse> getPlayerProfile(UUID uuid) {
            profileLookups++;
            return new CompletableFuture<>();
        }

        @Override
        public CompletableFuture<CreateTicketResponse> createTicket(CreateTicketRequest request) {
            tickets.add(request);
            return new CompletableFuture<>();
        }
    }

    private static final class ManualExecutor implements Executor {
        private final Deque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        void runAll() {
            while (!tasks.isEmpty()) tasks.poll().run();
        }
    }
}
