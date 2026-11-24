package gg.modl.backend.auth;

import org.mockito.InOrder;
import org.mockito.ArgumentMatchers;
import java.util.function.Supplier;
import gg.modl.backend.database.UnitOfWork;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.inOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.data.AuthCode;
import gg.modl.backend.auth.repository.AuthCodeRepository;
import gg.modl.backend.email.EmailHTMLTemplate;
import gg.modl.backend.email.EmailService;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeCodec;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuthServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final String EMAIL = "mod@example.com";

    private EmailService emailService;
    private AuthCodeRepository repository;
    private OneTimeCodeCodec codec;
    private LoginLockoutNotice lockoutNotice;
    private UnitOfWork unitOfWork;
    private AuthService service;
    private Server server;

    @BeforeEach
    void setUp() {
        emailService = mock(EmailService.class);
        repository = mock(AuthCodeRepository.class);
        codec = new OneTimeCodeCodec(() -> "secret");
        AuthConfiguration configuration = new AuthConfiguration();
        configuration.setEmailCodeExpiry(600);
        configuration.setEmailCodeCooldownSeconds(60);
        configuration.setEmailCodeFailureLimit(20);
        configuration.setEmailCodeFailureWindowSeconds(Duration.ofHours(24).toSeconds());
        server = TestServers.server("Server", "owner@example.com");
        lockoutNotice = mock(LoginLockoutNotice.class);
        unitOfWork = spy(new ImmediateUnitOfWork());
        service = new AuthService(emailService, repository, configuration, codec, lockoutNotice, unitOfWork,
            Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.find(EMAIL)).thenReturn(Optional.empty());
    }

    @Test
    void issuingIsAConditionalWriteGuardedByTheCooldownAndAnnouncesTheConfiguredExpiry() {
        when(repository.issue(eq(EMAIL), anyString(), any(), any(), any())).thenReturn(true);

        service.sendUserLoginCode(server, EMAIL);

        verify(repository).issue(eq(EMAIL), anyString(), eq(NOW),
            eq(NOW.plusSeconds(600)), eq(NOW.minusSeconds(60)));
        ArgumentCaptor<EmailHTMLTemplate.HTMLEmail> email = ArgumentCaptor.forClass(EmailHTMLTemplate.HTMLEmail.class);
        ArgumentCaptor<String> consoleNotice = ArgumentCaptor.forClass(String.class);
        verify(emailService).send(eq(EMAIL), email.capture(), consoleNotice.capture());
        assertThat(email.getValue().body()).contains("This code will expire in 10 minutes.");
        assertThat(consoleNotice.getValue()).endsWith("(valid 10 minutes)");
    }

    @Test
    void aCodeStillInItsCooldownSendsNothing() {
        when(repository.issue(eq(EMAIL), anyString(), any(), any(), any())).thenReturn(false);

        service.sendUserLoginCode(server, EMAIL);

        verify(emailService, never()).send(anyString(), any(), anyString());
    }

    @Test
    void anExhaustedFailureBudgetSendsNothingTellsTheOperatorAndRejectsCodesLikeAnyWrongCode() {
        when(repository.find(EMAIL)).thenReturn(Optional.of(AuthCode.builder()
            .email(EMAIL).recentFailures(20).failureWindowStartedAt(NOW.minus(Duration.ofHours(23))).build()));

        service.sendUserLoginCode(server, EMAIL);

        assertThat(service.verifyCode(server, EMAIL, "123456")).isFalse();
        verify(repository, never()).issue(any(), any(), any(), any(), any());
        verify(emailService, never()).send(anyString(), any(), anyString());
        verify(repository).consumeIfHashMatches(eq(EMAIL), anyString(), any(), any(), anyInt());
        verify(lockoutNotice, times(2)).announce(EMAIL, NOW.plus(Duration.ofHours(1)), NOW);
    }

    @Test
    void anExpiredFailureWindowNoLongerBlocks() {
        when(repository.find(EMAIL)).thenReturn(Optional.of(AuthCode.builder()
            .email(EMAIL).recentFailures(20).failureWindowStartedAt(NOW.minus(Duration.ofHours(25))).build()));
        when(repository.issue(eq(EMAIL), anyString(), any(), any(), any())).thenReturn(true);

        service.sendUserLoginCode(server, EMAIL);

        verify(emailService).send(eq(EMAIL), any(), anyString());
        verify(lockoutNotice, never()).announce(any(), any(), any());
    }

    @Test
    void aWrongCodeIsRecordedAgainstTheRollingFailureWindow() {
        when(repository.consumeIfHashMatches(eq(EMAIL), anyString(), any(), any(), anyInt()))
            .thenReturn(Optional.empty());

        assertThat(service.verifyCode(server, EMAIL, "000000")).isFalse();

        verify(repository).consumeIfHashMatches(EMAIL, codec.hash("000000"), NOW,
            NOW.minus(Duration.ofHours(24)), 20);
        verify(repository).recordFailedAttempt(EMAIL, NOW,
            NOW.minus(Duration.ofHours(24)), NOW.plus(Duration.ofHours(24)));
    }

    @Test
    void theWrongCodeThatReachesTheLimitTellsTheOperator() {
        when(repository.find(EMAIL)).thenReturn(Optional.of(AuthCode.builder()
            .email(EMAIL).recentFailures(20).failureWindowStartedAt(NOW).build()));

        assertThat(service.verifyCode(server, EMAIL, "000000")).isFalse();

        verify(lockoutNotice).announce(EMAIL, NOW.plus(Duration.ofHours(24)), NOW);
    }

    @Test
    void theRightCodeSignsInWithoutRecordingAFailure() {
        when(repository.consumeIfHashMatches(eq(EMAIL), eq(codec.hash("123456")), any(), any(), anyInt()))
            .thenReturn(Optional.of(AuthCode.builder().email(EMAIL).build()));

        assertThat(service.verifyCode(server, EMAIL, "123456")).isTrue();
        verify(repository, never()).recordFailedAttempt(any(), any(), any(), any());
    }

    @Test
    void aGuessIsJudgedAndCountedInOneUnitThatLocksTheCodeFirst() {
        when(repository.consumeIfHashMatches(eq(EMAIL), anyString(), any(), any(), anyInt())).thenReturn(Optional.empty());

        assertThat(service.verifyCode(server, EMAIL, "000000")).isFalse();

        InOrder order = inOrder(unitOfWork, repository);
        order.verify(unitOfWork).write(ArgumentMatchers.<Supplier<Boolean>>any());
        order.verify(repository).lock(EMAIL);
        order.verify(repository).consumeIfHashMatches(eq(EMAIL), anyString(), any(), any(), anyInt());
        order.verify(repository).recordFailedAttempt(eq(EMAIL), any(), any(), any());
        verify(unitOfWork, times(1)).write(ArgumentMatchers.<Supplier<Boolean>>any());
    }
}
