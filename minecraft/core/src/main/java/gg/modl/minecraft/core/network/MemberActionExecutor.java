package gg.modl.minecraft.core.network;

import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.staff.action.FreezeActions;
import gg.modl.minecraft.core.staff.action.HackReportActions;
import gg.modl.minecraft.core.staff.action.InspectActions;
import gg.modl.minecraft.core.staff.action.StaffActionGate;
import gg.modl.minecraft.core.staff.action.StaffActor;
import gg.modl.minecraft.core.staff.action.StaffActors;
import gg.modl.minecraft.core.staff.action.StaffMenuActions;
import gg.modl.minecraft.core.staff.action.StaffModeActions;
import gg.modl.minecraft.core.staff.action.StaffRequirement;
import gg.modl.minecraft.core.staff.action.TargetActions;
import gg.modl.minecraft.core.staff.action.VanishActions;
import gg.modl.minecraft.core.util.PluginLogger;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class MemberActionExecutor {
    private static final String LOCAL_MEMBER_KEY = "local";

    private final Executor asyncExecutor;
    private final Platform platform;
    private final LocaleManager localeManager;
    private final ModerationSessionService moderation;
    private final CommandRootRegistry commandRoots;
    private final StaffActionGate gate;
    private final ModlConsoleCommands consoleCommands;
    private final PluginLogger logger;
    private final Map<MemberActionKind, PlayerActionHandler> playerActions;
    private final StaffModeActions staffModeActions;
    private final TokenBuckets memberBudget;
    private final TokenBuckets actorBudget;
    private final TokenBuckets proxyCommandBudget;

    @Builder
    private MemberActionExecutor(Executor asyncExecutor, Platform platform, LocaleManager localeManager,
                                 ModerationSessionService moderation, CommandRootRegistry commandRoots,
                                 StaffActionGate gate, ModlConsoleCommands consoleCommands, PluginLogger logger,
                                 FreezeActions freezeActions, VanishActions vanishActions, TargetActions targetActions,
                                 StaffMenuActions staffMenuActions, InspectActions inspectActions,
                                 HackReportActions hackReportActions, StaffModeActions staffModeActions,
                                 LongSupplier nanoClock) {
        this.asyncExecutor = asyncExecutor;
        this.platform = platform;
        this.localeManager = localeManager;
        this.moderation = moderation;
        this.commandRoots = commandRoots;
        this.gate = gate;
        this.consoleCommands = consoleCommands;
        this.logger = logger;
        this.staffModeActions = staffModeActions;
        LongSupplier clock = nanoClock != null ? nanoClock : System::nanoTime;
        this.memberBudget = new TokenBuckets(MemberActionLimits.PER_MEMBER_PER_SECOND, clock);
        this.actorBudget = new TokenBuckets(MemberActionLimits.PER_ACTOR_PER_SECOND, clock);
        this.proxyCommandBudget = new TokenBuckets(MemberActionLimits.PROXY_COMMANDS_PER_MEMBER_PER_SECOND, clock);
        this.playerActions = playerActions(freezeActions, vanishActions, targetActions, staffMenuActions,
                inspectActions, hackReportActions);
    }

    public void submit(MemberAction action, ActionOrigin origin, Consumer<MemberActionOutcome> onResult) {
        asyncExecutor.execute(() -> onResult.accept(execute(action, origin)));
    }

    MemberActionOutcome execute(MemberAction action, ActionOrigin origin) {
        MemberActionKind kind = action.getKind();
        if (kind == null) return invalid(action, "unknown action");
        String requestId = action.getRequestId();
        if (requestId.isEmpty() || requestId.length() > MemberAction.MAX_REQUEST_ID_LENGTH) {
            return invalid(action, "bad request id");
        }
        return kind.isPlayerAction() ? executePlayerAction(action, kind, origin) : executeProxyCommand(action, kind);
    }

    private MemberActionOutcome executePlayerAction(MemberAction action, MemberActionKind kind, ActionOrigin origin) {
        UUID actor = action.getActor();
        if (actor == null) return invalid(action, "no actor");
        if (kind.carriesTarget() && action.getTarget() == null) return invalid(action, "no target");
        if (action.getCommandLine() != null) return invalid(action, "a command line on a player action");
        if (!memberBudget.tryAcquire(memberKey(action)) || !actorBudget.tryAcquire(actor.toString())) {
            return MemberActionOutcome.RATE_LIMITED;
        }
        return guarded(action, kind, () -> kind == MemberActionKind.STAFF_MODE_REFUSED
                ? runStaffModeRefusal(action, actor, origin)
                : runPlayerAction(action, kind, actor, origin));
    }

    private MemberActionOutcome executeProxyCommand(MemberAction action, MemberActionKind kind) {
        String commandLine = action.getCommandLine();
        if (commandLine == null) return invalid(action, "no command line");
        String invalidReason = invalidCommandLine(commandLine);
        if (invalidReason != null) return invalid(action, invalidReason);
        String member = memberKey(action);
        if (!memberBudget.tryAcquire(member) || !proxyCommandBudget.tryAcquire(member)) {
            return MemberActionOutcome.RATE_LIMITED;
        }
        return guarded(action, kind, () -> runProxyCommand(action, commandLine));
    }

    private MemberActionOutcome invalid(MemberAction action, String reason) {
        logger.debug("[network] Ignored an invalid staff action from " + memberKey(action) + ": " + reason);
        return MemberActionOutcome.INVALID;
    }

    private MemberActionOutcome guarded(MemberAction action, MemberActionKind kind, Supplier<MemberActionOutcome> run) {
        try {
            return run.get();
        } catch (RuntimeException failure) {
            logger.warning("[network] Staff action " + kind + " from " + memberKey(action) + " failed: " + failure);
            return MemberActionOutcome.INVALID;
        }
    }

    private static @Nullable String invalidCommandLine(String commandLine) {
        if (commandLine.trim().isEmpty()) return "no command line";
        if (commandLine.length() > MemberAction.MAX_COMMAND_LINE_LENGTH) return "command line too long";
        for (int i = 0; i < commandLine.length(); i++) {
            if (Character.isISOControl(commandLine.charAt(i))) return "control characters in the command line";
        }
        return null;
    }

    private MemberActionOutcome runPlayerAction(MemberAction action, MemberActionKind kind, UUID actorUuid,
                                                ActionOrigin origin) {
        if (!platform.isOnline(actorUuid)) return MemberActionOutcome.DENIED;
        StaffActor actor = StaffActors.player(actorUuid, platform);
        if (origin == ActionOrigin.NETWORK && !isOnMember(actorUuid, action.getMemberName())) {
            AbstractPlayer player = platform.getPlayer(actorUuid);
            logger.warning("[network] Refused a staff action from " + action.getMemberName() + ": "
                    + (player != null ? player.getUsername() : actorUuid) + " is on "
                    + platform.getPlayerServer(actorUuid)
                    + ". Name the server key or server-name after this server's name in the proxy config.");
            return unavailable(actor);
        }
        PlayerActionHandler handler = handlerFor(kind);
        if (!moderation.isInStaffMode(actorUuid) || !commandRoots.isEnabled(handler.rootKey)) {
            return unavailable(actor);
        }
        Optional<AbstractPlayer> target = resolveTarget(action, kind, actorUuid, origin);
        if (handler.needsTarget && !target.isPresent()) return MemberActionOutcome.INVALID;
        if (!gate.authorize(actor, handler.requirement)) return MemberActionOutcome.DENIED;
        handler.action.run(actor, target.orElse(null));
        return MemberActionOutcome.ACCEPTED;
    }

    private PlayerActionHandler handlerFor(MemberActionKind kind) {
        PlayerActionHandler handler = playerActions.get(kind);
        if (handler == null) throw new IllegalStateException("No player action handler is registered for " + kind);
        return handler;
    }

    private MemberActionOutcome runStaffModeRefusal(MemberAction action, UUID actorUuid, ActionOrigin origin) {
        if (!platform.isOnline(actorUuid)) return MemberActionOutcome.INVALID;
        if (origin == ActionOrigin.NETWORK && !isOnMember(actorUuid, action.getMemberName())) {
            return MemberActionOutcome.DENIED;
        }
        return staffModeActions.refused(StaffActors.player(actorUuid, platform))
                ? MemberActionOutcome.ACCEPTED
                : MemberActionOutcome.INVALID;
    }

    private Optional<AbstractPlayer> resolveTarget(MemberAction action, MemberActionKind kind, UUID actor,
                                                   ActionOrigin origin) {
        UUID target = kind.carriesTarget() ? action.getTarget() : moderation.targetOf(actor);
        if (target == null || !platform.isOnline(target)) return Optional.empty();
        if (kind.carriesTarget() && origin == ActionOrigin.NETWORK && !isOnMember(target, action.getMemberName())) {
            return Optional.empty();
        }
        return Optional.ofNullable(platform.getAbstractPlayer(target, false));
    }

    private MemberActionOutcome runProxyCommand(MemberAction action, String commandLine) {
        if (!action.isProxyCommandGranted()) return MemberActionOutcome.NOT_PERMITTED;
        String root = CommandRootRegistry.firstToken(commandLine);
        if (!commandRoots.rootKeyOf(commandLine).isPresent()) {
            logger.warning("[network] Refused /" + root + " from " + action.getMemberName()
                    + " console: only modl commands run on the proxy.");
            return MemberActionOutcome.DENIED;
        }
        logger.info("[network] Running /" + root + " for " + action.getMemberName() + " console");
        consoleCommands.dispatch(withoutLeadingSlash(commandLine.trim()));
        return MemberActionOutcome.ACCEPTED;
    }

    private MemberActionOutcome unavailable(StaffActor actor) {
        actor.reply(localeManager.getMessage("network.action_unavailable"));
        return MemberActionOutcome.DENIED;
    }

    private boolean isOnMember(UUID player, @Nullable String memberName) {
        String server = platform.getPlayerServer(player);
        return server != null && memberName != null && server.equalsIgnoreCase(memberName);
    }

    private static String memberKey(MemberAction action) {
        String memberName = action.getMemberName();
        return memberName != null ? memberName : LOCAL_MEMBER_KEY;
    }

    private static String withoutLeadingSlash(String commandLine) {
        return commandLine.startsWith("/") ? commandLine.substring(1) : commandLine;
    }

    private static Map<MemberActionKind, PlayerActionHandler> playerActions(
            FreezeActions freeze, VanishActions vanish, TargetActions target, StaffMenuActions staffMenu,
            InspectActions inspect, HackReportActions hackReport) {
        Map<MemberActionKind, PlayerActionHandler> handlers = new EnumMap<>(MemberActionKind.class);
        handlers.put(MemberActionKind.TOGGLE_FREEZE_TARGET, PlayerActionHandler.targeted("freeze",
                FreezeActions.REQUIREMENT, freeze::toggle));
        handlers.put(MemberActionKind.TOGGLE_VANISH, PlayerActionHandler.untargeted("vanish",
                VanishActions.REQUIREMENT, vanish::toggle));
        handlers.put(MemberActionKind.SELECT_TARGET, PlayerActionHandler.targeted("target",
                TargetActions.REQUIREMENT, target::select));
        handlers.put(MemberActionKind.CLEAR_TARGET, PlayerActionHandler.untargeted("target",
                TargetActions.REQUIREMENT, target::clear));
        handlers.put(MemberActionKind.OPEN_STAFF_MENU, PlayerActionHandler.untargeted("staffmenu",
                StaffMenuActions.REQUIREMENT, staffMenu::openStaffMenu));
        handlers.put(MemberActionKind.INSPECT_TARGET, PlayerActionHandler.targeted("inspect",
                InspectActions.REQUIREMENT, (actor, player) -> inspect.openMenu(actor, player.getUsername())));
        handlers.put(MemberActionKind.HACKREPORT_TARGET, PlayerActionHandler.targeted("hackreport",
                HackReportActions.REQUIREMENT, (actor, player) -> hackReport.report(actor, player, null)));
        return handlers;
    }

    private interface PlayerAction {
        void run(StaffActor actor, @Nullable AbstractPlayer target);
    }

    private static final class PlayerActionHandler {
        private final String rootKey;
        private final StaffRequirement requirement;
        private final boolean needsTarget;
        private final PlayerAction action;

        private PlayerActionHandler(String rootKey, StaffRequirement requirement, boolean needsTarget,
                                    PlayerAction action) {
            this.rootKey = rootKey;
            this.requirement = requirement;
            this.needsTarget = needsTarget;
            this.action = action;
        }

        static PlayerActionHandler targeted(String rootKey, StaffRequirement requirement,
                                            BiConsumer<StaffActor, AbstractPlayer> action) {
            return new PlayerActionHandler(rootKey, requirement, true, (actor, target) -> {
                if (target == null) throw new IllegalStateException(rootKey + " ran without its resolved target");
                action.accept(actor, target);
            });
        }

        static PlayerActionHandler untargeted(String rootKey, StaffRequirement requirement,
                                              Consumer<StaffActor> action) {
            return new PlayerActionHandler(rootKey, requirement, false, (actor, ignored) -> action.accept(actor));
        }
    }
}
