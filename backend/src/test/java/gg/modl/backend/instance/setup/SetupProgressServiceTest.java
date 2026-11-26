package gg.modl.backend.instance.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.AuthFailures;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.instance.InstanceProperties;
import gg.modl.backend.instance.InstanceService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.SetupProgress;
import gg.modl.backend.server.data.SetupStep;
import gg.modl.backend.server.data.SetupStepStatus;
import gg.modl.backend.support.TestServers;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SetupProgressServiceTest {
    private InstanceContext instanceContext;
    private InstanceService instanceService;
    private SetupCodeService setupCodeService;
    private OwnerSignInReadiness ownerSignInReadiness;

    @BeforeEach
    void setUp() {
        instanceContext = mock(InstanceContext.class);
        instanceService = mock(InstanceService.class);
        setupCodeService = mock(SetupCodeService.class);
        ownerSignInReadiness = mock(OwnerSignInReadiness.class);
    }

    @Test
    void theWizardListsEveryStepInOrderAndIsPendingUntilCompleted() {
        givenInstance(server("owner@example.com",
            new SetupProgress(Instant.now(), Instant.now(), null, Map.of(SetupStep.ACCOUNT.name(), SetupStepStatus.DONE.name(), "RETIRED", "WHATEVER"))));

        SetupWizard wizard = service(properties()).state(false);

        assertThat(wizard.completed()).isFalse();
        assertThat(wizard.steps().keySet()).containsExactly(SetupStep.values());
        assertThat(wizard.steps().get(SetupStep.ACCOUNT)).isEqualTo(SetupStepStatus.DONE);
        assertThat(wizard.steps().get(SetupStep.CONTENT)).isEqualTo(SetupStepStatus.PENDING);
    }

    @Test
    void anInstanceSetUpBeforeTheWizardNeverAutoOpensIt() {
        givenInstance(server("owner@example.com", null));

        assertThat(service(properties()).state(false).completed()).isTrue();
    }

    @Test
    void forwardedHeadersSuggestAReverseProxyOnlyWhileTheyAreNotTrusted() {
        givenInstance(server("owner@example.com", null));

        assertThat(service(properties()).state(true).environment().reverseProxySuspected()).isTrue();
        assertThat(service(new InstanceProperties(null, null, null, null, null, true)).state(true).environment().reverseProxySuspected())
            .isFalse();
        assertThat(service(properties()).state(false).environment().reverseProxySuspected()).isFalse();
    }

    @Test
    void anOwnerSessionRetiresTheCodeButLeavesTheSignInStepOpen() {
        givenInstance(server("owner@example.com", new SetupProgress(Instant.now(), null, null, null)));

        service(properties()).onOwnerSessionIssued();

        verify(setupCodeService).revoke();
        verify(instanceService, never()).recordOwnerSignIn();
    }

    @Test
    void laterOwnerSessionsSkipTheDatabaseEntirely() {
        givenInstance(server("owner@example.com", new SetupProgress(Instant.now(), Instant.now(), null, null)));

        service(properties()).onOwnerSessionIssued();

        verify(instanceService, never()).recordOwnerSignIn();
        verify(setupCodeService, never()).revoke();
    }

    @Test
    void aFailureToRetireTheCodeNeverFailsTheLogin() {
        givenInstance(server("owner@example.com", new SetupProgress(Instant.now(), null, null, null)));
        doThrow(new IllegalStateException("database unavailable")).when(setupCodeService).revoke();

        service(properties()).onOwnerSessionIssued();

        verify(setupCodeService).revoke();
    }

    @Test
    void confirmingTheSignInStepRecordsTheOwnerAndRetiresTheCode() {
        Server server = server("owner@example.com", new SetupProgress(Instant.now(), null, null, null));
        givenInstance(server);
        when(instanceService.recordOwnerSignIn()).thenReturn(true);

        service(properties()).updateStep(SetupStep.ACCOUNT, SetupStepStatus.DONE, false);

        verify(ownerSignInReadiness).require(server);
        verify(setupCodeService).revoke();
        verify(instanceService, never()).updateSetupStep(SetupStep.ACCOUNT, SetupStepStatus.DONE);
    }

    @Test
    void noStepCanBeSkipped() {
        givenInstance(server("owner@example.com", new SetupProgress(Instant.now(), null, null, null)));

        assertThatThrownBy(() -> service(properties()).updateStep(SetupStep.ACCOUNT, SetupStepStatus.SKIPPED, false))
            .isInstanceOf(ValidationException.class)
            .extracting(failure -> ((ValidationException) failure).getMachineCode())
            .isEqualTo("SETUP_STEP_NOT_SKIPPABLE");
        verify(instanceService, never()).recordOwnerSignIn();
    }

    @Test
    void stepsAreCompletedInOrder() {
        givenInstance(server("owner@example.com", new SetupProgress(Instant.now(), Instant.now(), null, null)));

        assertThatThrownBy(() -> service(properties()).updateStep(SetupStep.IDENTITY, SetupStepStatus.DONE, false))
            .isInstanceOf(ConflictException.class)
            .extracting(failure -> ((ConflictException) failure).getMachineCode())
            .isEqualTo("SETUP_STEP_OUT_OF_ORDER");
        verify(instanceService, never()).updateSetupStep(SetupStep.IDENTITY, SetupStepStatus.DONE);
    }

    @Test
    void theLastStepFinishesTheSetup() {
        Map<String, String> allButContent = everyStepDoneExcept(SetupStep.CONTENT);
        givenInstance(server("owner@example.com", new SetupProgress(Instant.now(), Instant.now(), null, allButContent)));
        when(instanceService.completeSetup())
            .thenReturn(server("owner@example.com", new SetupProgress(Instant.now(), Instant.now(), Instant.now(), allButContent)));

        assertThat(service(properties()).updateStep(SetupStep.CONTENT, SetupStepStatus.DONE, false).completed()).isTrue();
        verify(instanceService).updateSetupStep(SetupStep.CONTENT, SetupStepStatus.DONE);
        verify(instanceService).completeSetup();
    }

    @Test
    void anEarlierStepDoesNotFinishTheSetup() {
        Map<String, String> accountOnly = Map.of(SetupStep.ACCOUNT.name(), SetupStepStatus.DONE.name());
        givenInstance(server("owner@example.com", new SetupProgress(Instant.now(), Instant.now(), null, accountOnly)));
        when(instanceService.updateSetupStep(SetupStep.IMPORT, SetupStepStatus.DONE))
            .thenReturn(server("owner@example.com", new SetupProgress(Instant.now(), Instant.now(), null, accountOnly)));

        assertThat(service(properties()).updateStep(SetupStep.IMPORT, SetupStepStatus.DONE, false).completed()).isFalse();
        verify(instanceService, never()).completeSetup();
    }

    @Test
    void aCompletedWizardCanRevisitAnyStep() {
        givenInstance(server("owner@example.com", new SetupProgress(Instant.now(), Instant.now(), Instant.now(), null)));
        when(instanceService.updateSetupStep(SetupStep.EMAIL, SetupStepStatus.DONE))
            .thenReturn(server("owner@example.com", new SetupProgress(Instant.now(), Instant.now(), Instant.now(), null)));

        service(properties()).updateStep(SetupStep.EMAIL, SetupStepStatus.DONE, false);

        verify(instanceService).updateSetupStep(SetupStep.EMAIL, SetupStepStatus.DONE);
        verify(instanceService, never()).completeSetup();
    }

    @Test
    void anOwnerWhoCouldNotSignInAgainCannotConfirmTheSignInStep() {
        Server server = server("owner@example.com", new SetupProgress(Instant.now(), null, null, null));
        givenInstance(server);
        doThrow(AuthFailures.ownerPasswordRequired()).when(ownerSignInReadiness).require(server);

        assertThatThrownBy(() -> service(properties()).updateStep(SetupStep.ACCOUNT, SetupStepStatus.DONE, false))
            .isInstanceOf(ConflictException.class);
        verify(instanceService, never()).recordOwnerSignIn();
        verify(setupCodeService, never()).revoke();
    }

    @Test
    void aCompletedWizardReopensAtTheSignInStepWhenTheOwnerChanged() {
        givenInstance(server("new-owner@example.com",
            new SetupProgress(Instant.now(), null, Instant.now(), Map.of(SetupStep.ACCOUNT.name(), SetupStepStatus.DONE.name()))));

        SetupWizard wizard = service(properties()).state(false);

        assertThat(wizard.completed()).isFalse();
        assertThat(wizard.steps().get(SetupStep.ACCOUNT)).isEqualTo(SetupStepStatus.PENDING);
    }

    @Test
    void anActiveImportBlocksTheImportStepAndTheFinishingStep() {
        SetupBlocker importRunning = new SetupBlocker() {
            @Override
            public Optional<String> blockingCompletion() {
                return Optional.of("IMPORT_ACTIVE");
            }

            @Override
            public Optional<String> blockingStep(SetupStep step) {
                return step == SetupStep.IMPORT ? Optional.of("IMPORT_ACTIVE") : Optional.empty();
            }
        };
        SetupProgressService service = new SetupProgressService(instanceContext, instanceService, properties(), setupCodeService,
            ownerSignInReadiness, List.of(importRunning), List.of());
        Map<String, String> allButContent = everyStepDoneExcept(SetupStep.CONTENT);
        givenInstance(server("owner@example.com", new SetupProgress(Instant.now(), Instant.now(), null, allButContent)));

        assertThatThrownBy(() -> service.updateStep(SetupStep.CONTENT, SetupStepStatus.DONE, false)).isInstanceOf(ConflictException.class)
            .extracting(failure -> ((ConflictException) failure).getMachineCode()).isEqualTo("IMPORT_ACTIVE");
        assertThatThrownBy(() -> service.updateStep(SetupStep.IMPORT, SetupStepStatus.DONE, false)).isInstanceOf(ConflictException.class);
        verify(instanceService, never()).completeSetup();
        verify(instanceService, never()).updateSetupStep(SetupStep.IMPORT, SetupStepStatus.DONE);
    }

    @Test
    void theImportStepIsSecondAndListed() {
        assertThat(SetupStep.values()).startsWith(SetupStep.ACCOUNT, SetupStep.IMPORT);
        assertThat(SetupStep.fromPathName("import")).contains(SetupStep.IMPORT);
    }

    @Test
    void thePlayerAddressStepSitsBetweenStaffAndAiAndIsListed() {
        List<SetupStep> order = List.of(SetupStep.values());

        assertThat(order.indexOf(SetupStep.PLAYER_ADDRESSES)).isEqualTo(order.indexOf(SetupStep.STAFF) + 1);
        assertThat(order.indexOf(SetupStep.AI)).isEqualTo(order.indexOf(SetupStep.PLAYER_ADDRESSES) + 1);
        assertThat(SetupStep.fromPathName("player-addresses")).contains(SetupStep.PLAYER_ADDRESSES);
    }

    private static Map<String, String> everyStepDoneExcept(SetupStep open) {
        return Arrays.stream(SetupStep.values())
            .filter(step -> step != open)
            .collect(Collectors.toMap(SetupStep::name, step -> SetupStepStatus.DONE.name()));
    }

    private void givenInstance(Server server) {
        when(instanceContext.instance()).thenReturn(server);
    }

    private SetupProgressService service(InstanceProperties properties) {
        return new SetupProgressService(instanceContext, instanceService, properties, setupCodeService, ownerSignInReadiness, List.of(), List.of());
    }

    private static InstanceProperties properties() {
        return new InstanceProperties(null, null, null, null, null, null);
    }

    private static Server server(String adminEmail, SetupProgress setup) {
        Server server = TestServers.server("Server", adminEmail);
        server.setSetup(setup);
        return server;
    }
}
