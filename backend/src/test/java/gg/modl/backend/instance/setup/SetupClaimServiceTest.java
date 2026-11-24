package gg.modl.backend.instance.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.exception.BaseApplicationException;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.UnauthorizedException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.instance.InstanceService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.SetupProgress;
import gg.modl.backend.support.TestServers;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SetupClaimServiceTest {
    private static final String CODE = "ABCD-EFGH";
    private static final SetupClient CLIENT = SetupClient.identifiedBy("203.0.113.7");

    private InstanceContext instanceContext;
    private InstanceService instanceService;
    private SetupCodeService setupCodeService;
    private SetupClaimService service;

    @BeforeEach
    void setUp() {
        instanceContext = mock(InstanceContext.class);
        instanceService = mock(InstanceService.class);
        setupCodeService = mock(SetupCodeService.class);
        service = new SetupClaimService(instanceContext, instanceService, setupCodeService);
        when(instanceContext.instance()).thenReturn(server(null, null));
    }

    @Test
    void aValidCodeClaimsTheInstanceForTheNormalizedOwnerAndOrigin() {
        givenTheCodeMatches();
        when(instanceService.claim("owner@example.com", "https://panel.example.com")).thenReturn(true);

        assertThat(service.claim(CODE, " Owner@Example.com ", "HTTPS://Panel.Example.com/", CLIENT)).isEqualTo("owner@example.com");
    }

    @Test
    void anInvalidEmailOrPanelUrlIsRejectedBeforeTheCodeIsTouched() {
        assertThatThrownBy(() -> service.claim(CODE, "not-an-email", "https://panel.example.com", CLIENT))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.claim(CODE, "owner@example.com", "https://panel.example.com/path", CLIENT))
            .isInstanceOf(ValidationException.class);

        verifyNoInteractions(setupCodeService);
    }

    @ParameterizedTest
    @CsvSource({"INVALID, SETUP_CODE_INVALID", "LOCKED, SETUP_CODE_LOCKED", "NONE, SETUP_CODE_EXPIRED"})
    void aRejectedCodeNeverClaimsAndNamesTheReason(SetupCodeVerdict verdict, String machineCode) {
        when(setupCodeService.consume(eq(CODE), eq(CLIENT), any())).thenReturn(verdict);

        assertThatThrownBy(() -> service.claim(CODE, "owner@example.com", "https://panel.example.com", CLIENT))
            .isInstanceOf(UnauthorizedException.class)
            .extracting(failure -> ((BaseApplicationException) failure).getMachineCode())
            .isEqualTo(machineCode);
        verify(instanceService, never()).claim(anyString(), anyString());
    }

    @Test
    void aClaimedInstanceAnswersConflictBeforeAnyCodeIsChecked() {
        when(instanceContext.instance()).thenReturn(server("owner@example.com", null));

        assertThatThrownBy(() -> service.claim(CODE, "other@example.com", "https://panel.example.com", CLIENT))
            .isInstanceOf(ConflictException.class)
            .extracting(failure -> ((BaseApplicationException) failure).getMachineCode())
            .isEqualTo("SETUP_ALREADY_CLAIMED");
        verifyNoInteractions(setupCodeService);
    }

    @Test
    void losingTheClaimRaceAfterConsumingTheCodeIsAConflict() {
        givenTheCodeMatches();
        when(instanceService.claim("owner@example.com", "https://panel.example.com")).thenReturn(false);

        assertThatThrownBy(() -> service.claim(CODE, "owner@example.com", "https://panel.example.com", CLIENT))
            .isInstanceOf(ConflictException.class);
    }

    @Test
    void aPresetOwnerSignsInWithTheCodeAsTheConfiguredEmail() {
        Server preset = server("Owner@Example.com", new SetupProgress(Instant.now(), null, null, null));
        when(instanceContext.instance()).thenReturn(preset);
        when(instanceService.storedInstance()).thenReturn(preset);
        givenTheCodeMatches();

        assertThat(service.signInOwner(CODE, CLIENT)).isEqualTo("owner@example.com");
    }

    @Test
    void anOwnerWhoSignedInWhileTheCodeWasEnteredTurnsTheCodeSignInIntoAConflict() {
        when(instanceService.storedInstance())
            .thenReturn(server("owner@example.com", new SetupProgress(Instant.now(), Instant.now(), null, null)));
        when(instanceContext.instance()).thenReturn(server("owner@example.com", new SetupProgress(Instant.now(), null, null, null)));
        givenTheCodeMatches();

        assertThatThrownBy(() -> service.signInOwner(CODE, CLIENT)).isInstanceOf(ConflictException.class);
    }

    @Test
    void codeSignInIsUnavailableOnceTheOwnerHasSignedIn() {
        when(instanceContext.instance()).thenReturn(server("owner@example.com", new SetupProgress(Instant.now(), Instant.now(), null, null)));

        assertThatThrownBy(() -> service.signInOwner(CODE, CLIENT))
            .isInstanceOf(ConflictException.class)
            .extracting(failure -> ((BaseApplicationException) failure).getMachineCode())
            .isEqualTo("SETUP_SIGN_IN_NOT_AVAILABLE");
        verifyNoInteractions(setupCodeService);
    }

    @Test
    void codeSignInIsUnavailableBeforeAnyoneOwnsTheInstance() {
        assertThatThrownBy(() -> service.signInOwner(CODE, CLIENT)).isInstanceOf(ConflictException.class);
    }

    private void givenTheCodeMatches() {
        when(setupCodeService.consume(eq(CODE), eq(CLIENT), any())).thenAnswer(invocation -> {
            invocation.getArgument(2, Runnable.class).run();
            return SetupCodeVerdict.CONSUMED;
        });
    }

    private static Server server(String adminEmail, SetupProgress setup) {
        Server server = TestServers.server("Server", adminEmail);
        server.setSetup(setup);
        return server;
    }
}
