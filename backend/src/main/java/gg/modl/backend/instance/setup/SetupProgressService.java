package gg.modl.backend.instance.setup;

import gg.modl.backend.auth.session.OwnerSessionIssuedEvent;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.instance.InstanceProperties;
import gg.modl.backend.instance.InstanceService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.SetupProgress;
import gg.modl.backend.server.data.SetupStep;
import gg.modl.backend.server.data.SetupStepStatus;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class SetupProgressService {
    private final InstanceContext instanceContext;
    private final InstanceService instanceService;
    private final InstanceProperties instanceProperties;
    private final SetupCodeService setupCodeService;
    private final OwnerSignInReadiness ownerSignInReadiness;
    private final List<SetupBlocker> setupBlockers;
    private final List<SetupStepPrerequisite> prerequisites;

    public SetupWizard state(boolean forwardedHeadersSeen) {
        return wizardOf(instanceContext.instance(), forwardedHeadersSeen);
    }

    public SetupWizard updateStep(SetupStep step, SetupStepStatus status, boolean forwardedHeadersSeen) {
        if (status != SetupStepStatus.DONE) {
            throw new ValidationException("Setup steps cannot be skipped; complete each step", "SETUP_STEP_NOT_SKIPPABLE");
        }
        refuseWhileBlocked(setupBlockers.stream().map(blocker -> blocker.blockingStep(step)).toList());
        Server current = instanceContext.instance();
        requireEarlierStepsDone(current, step);
        prerequisites.stream().filter(prerequisite -> prerequisite.step() == step).forEach(SetupStepPrerequisite::require);
        boolean finishes = finishesSetup(current, step);
        if (finishes) {
            refuseWhileBlocked(setupBlockers.stream().map(SetupBlocker::blockingCompletion).toList());
        }
        Server updated = step == SetupStep.ACCOUNT ? confirmOwnerSignIn() : instanceService.updateSetupStep(step, SetupStepStatus.DONE);
        return wizardOf(finishes ? instanceService.completeSetup() : updated, forwardedHeadersSeen);
    }

    @EventListener(OwnerSessionIssuedEvent.class)
    public void onOwnerSessionIssued() {
        if (!SetupPhase.of(instanceContext.instance()).needsCode()) {
            return;
        }
        try {
            setupCodeService.revoke();
        } catch (RuntimeException e) {
            log.warn("Could not retire the setup code after the owner signed in; it stays valid until it expires", e);
        }
    }

    private Server confirmOwnerSignIn() {
        ownerSignInReadiness.require(instanceContext.instance());
        if (instanceService.recordOwnerSignIn()) {
            setupCodeService.revoke();
        }
        return instanceContext.instance();
    }

    private static void requireEarlierStepsDone(Server server, SetupStep step) {
        SetupProgress setup = server.getSetup();
        if (setup == null || isFinished(setup)) {
            return;
        }
        boolean earlierOpen = Arrays.stream(SetupStep.values())
            .filter(earlier -> earlier.compareTo(step) < 0)
            .anyMatch(earlier -> setup.statusOf(earlier) != SetupStepStatus.DONE);
        if (earlierOpen) {
            throw new ConflictException("Complete the earlier setup steps first", "SETUP_STEP_OUT_OF_ORDER");
        }
    }

    private static boolean finishesSetup(Server server, SetupStep step) {
        SetupProgress setup = server.getSetup();
        return setup != null && !isFinished(setup) && Arrays.stream(SetupStep.values())
            .filter(other -> other != step)
            .allMatch(other -> setup.statusOf(other) == SetupStepStatus.DONE);
    }

    private static boolean isFinished(SetupProgress setup) {
        return setup.isCompleted() && setup.ownerHasSignedIn();
    }

    private static void refuseWhileBlocked(List<Optional<String>> verdicts) {
        verdicts.stream().flatMap(Optional::stream).findFirst().ifPresent(code -> {
            throw new ConflictException("Setup cannot change while an import is running", code);
        });
    }

    private SetupWizard wizardOf(Server server, boolean forwardedHeadersSeen) {
        SetupProgress setup = server.getSetup();
        boolean completed = setup == null || isFinished(setup);
        Map<SetupStep, SetupStepStatus> steps = Arrays.stream(SetupStep.values())
            .collect(Collectors.toMap(Function.identity(), step -> setup == null ? SetupStepStatus.PENDING : setup.statusOf(step),
                (first, second) -> first, () -> new EnumMap<>(SetupStep.class)));
        return new SetupWizard(completed, steps, environment(forwardedHeadersSeen));
    }

    private SetupWizard.Environment environment(boolean forwardedHeadersSeen) {
        return new SetupWizard.Environment(
            SetupHost.of(instanceProperties),
            forwardedHeadersSeen && !instanceProperties.trustsProxyHeaders());
    }
}
